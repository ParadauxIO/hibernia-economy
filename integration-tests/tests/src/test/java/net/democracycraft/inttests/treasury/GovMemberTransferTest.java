package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delegated spend access on government accounts: a <em>member</em> of an account
 * can move money out of it (transfer / payout) to anyone, with a memo — but
 * cannot modify the account's membership. This is how access is handed out (add
 * a player or LP group as a member); managing members stays gated to authorizers
 * + {@code treasury.gov.account.manage}.
 */
class GovMemberTransferTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private static String acct(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    private static void waitForGovBalanceAtLeast(String account, BigDecimal min) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (Ledger.governmentBalance(account).orElse(BigDecimal.ZERO).compareTo(min) >= 0) return;
            try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("Gov account " + account + " never reached balance " + min);
    }

    @Test
    void memberCanPayoutFromAccountTheyBelongTo() {
        String owner = Usernames.fresh("GtO");
        String member = Usernames.fresh("GtM");
        String recipient = Usernames.fresh("GtR");
        String account = acct("Dept");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer m = MockPlayer.connectLocal(member);
             MockPlayer r = MockPlayer.connectLocal(recipient)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(m.getUuid(), TIMEOUT);
            BigDecimal recipientStart = Ledger.waitForPersonalBalance(r.getUuid(), TIMEOUT);

            final int[] acctId = new int[1];
            Ops.run(owner, () -> {
                o.chatCommand("/gov account create " + account);
                acctId[0] = Ledger.waitForGovernmentAccount(account, TIMEOUT);
                Ledger.asyncSettle();
                o.chatCommand("/gov account transfer DCGovernment " + account + " 1000 seed");
                waitForGovBalanceAtLeast(account, new BigDecimal("1000"));
                o.chatCommand("/gov account member add " + account + " " + member);
                Ledger.waitForMemberCount(acctId[0], 1, TIMEOUT);
            });

            // The member is NOT op. To invoke /gov at all they need the command
            // node treasury.gov (their LP "spend access" group carries it); the
            // per-account spend right then comes purely from being a member —
            // NOT from any gov.account.* admin node. This is the delegation model.
            net.democracycraft.inttests.support.RconClient.send(
                    "lp user " + member + " permission set treasury.gov true");
            try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            try {
                m.drainMessages();
                m.chatCommand("/gov payout " + account + " " + recipient + " 250 dept stipend");

                Ledger.waitForPersonalBalance(r.getUuid(),
                        recipientStart.add(new BigDecimal("250.00")), TIMEOUT);
                assertThat(Ledger.governmentBalance(account).orElseThrow())
                        .as("source gov account is debited by the member's payout")
                        .isEqualByComparingTo("750");
            } finally {
                net.democracycraft.inttests.support.RconClient.send(
                        "lp user " + member + " permission unset treasury.gov");
            }
        }
    }

    /**
     * The LP-group form of the same delegation: the group is added as a member with
     * {@code /gov account member addgroup}, and a player whose only link to the
     * account is belonging to that LuckPerms group can spend from it.
     */
    @Test
    void groupMemberCanPayoutFromAccountTheirGroupBelongsTo() {
        String owner = Usernames.fresh("GgO");
        String member = Usernames.fresh("GgM");
        String recipient = Usernames.fresh("GgR");
        String account = acct("DeptG");
        // Mixed case on purpose: staff type group names as they read them, while
        // LuckPerms always stores and reports them lower-cased.
        String typedGroup = "Dept" + Long.toHexString(Instant.now().toEpochMilli());
        String lpGroup = typedGroup.toLowerCase(java.util.Locale.ROOT);

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer m = MockPlayer.connectLocal(member);
             MockPlayer r = MockPlayer.connectLocal(recipient)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(m.getUuid(), TIMEOUT);
            BigDecimal recipientStart = Ledger.waitForPersonalBalance(r.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                o.chatCommand("/gov account create " + account);
                Ledger.waitForGovernmentAccount(account, TIMEOUT);
                Ledger.asyncSettle();
                o.chatCommand("/gov account transfer DCGovernment " + account + " 1000 seed");
                waitForGovBalanceAtLeast(account, new BigDecimal("1000"));
                o.drainMessages();
                o.chatCommand("/gov account member addgroup " + account + " " + typedGroup);
                o.awaitMessageContaining(typedGroup, TIMEOUT);
            });

            RconClient.send("lp creategroup " + lpGroup);
            // The group carries the command node, as a real "department" group would.
            RconClient.send("lp group " + lpGroup + " permission set treasury.gov true");
            RconClient.send("lp user " + member + " parent add " + lpGroup);
            try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            try {
                m.drainMessages();
                m.chatCommand("/gov payout " + account + " " + recipient + " 250 dept stipend");

                Ledger.waitForPersonalBalance(r.getUuid(),
                        recipientStart.add(new BigDecimal("250.00")), TIMEOUT);
                assertThat(Ledger.governmentBalance(account).orElseThrow())
                        .as("source gov account is debited by the group member's payout")
                        .isEqualByComparingTo("750");
            } finally {
                RconClient.send("lp user " + member + " parent remove " + lpGroup);
                RconClient.send("lp deletegroup " + lpGroup);
            }
        }
    }

    @Test
    void memberCannotModifyMembers() {
        String owner = Usernames.fresh("GnO");
        String member = Usernames.fresh("GnM");
        String outsider = Usernames.fresh("GnX");
        String account = acct("Dpt2");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer m = MockPlayer.connectLocal(member);
             MockPlayer x = MockPlayer.connectLocal(outsider)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(m.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(x.getUuid(), TIMEOUT);

            final int[] acctId = new int[1];
            Ops.run(owner, () -> {
                o.chatCommand("/gov account create " + account);
                acctId[0] = Ledger.waitForGovernmentAccount(account, TIMEOUT);
                Ledger.asyncSettle();
                o.chatCommand("/gov account member add " + account + " " + member);
                Ledger.waitForMemberCount(acctId[0], 1, TIMEOUT);
            });

            // A plain member (not an authorizer, no treasury.gov.account.manage)
            // tries to add another member — must have no effect.
            m.chatCommand("/gov account member add " + account + " " + outsider);
            Ledger.asyncSettle();
            assertThat(Ledger.memberCount(acctId[0]))
                    .as("a plain member must not be able to add other members")
                    .isEqualTo(1);
        }
    }
}
