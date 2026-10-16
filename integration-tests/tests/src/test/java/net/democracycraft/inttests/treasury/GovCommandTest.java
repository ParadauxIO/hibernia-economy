package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Await;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Treasury's {@code /government} (alias {@code /gov}) command. Covers all
 * sub-routes: account lifecycle, transfer / pay / payout, member + auth
 * management, history viewing.
 *
 * <p>{@code treasury.gov.*} is {@code default: false} so an op alone doesn't
 * suffice; {@link Ops#run} additionally grants {@code treasury.*} via LuckPerms.
 *
 * <p>Each test names a fresh GOVERNMENT account so it doesn't collide with
 * primitive accounts (starting-balances, DCGovernment, GovernmentFines) or
 * with other test runs.
 */
class GovCommandTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshAccountName(String tag) {
        // Treasury's account-name validation rejects hyphens (department
        // accounts use a tighter pattern); use a digit separator instead.
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    // --- account create / archive / list / balance --------------------------

    @Test
    void accountCreateAndArchive() {
        String admin = Usernames.fresh("GcA");
        String name = freshAccountName("CrAr");

        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + name);
                a.awaitMessageContaining("Created", TIMEOUT);
                // Treasury's @Async commands send the chat receipt before
                // their MyBatis transaction commits — poll the DB instead of
                // a direct read.
                Ledger.waitForGovernmentAccount(name, TIMEOUT);

                a.drainMessages();
                a.chatCommand("/gov account archive " + name);
                a.awaitMessageContaining("Archived", TIMEOUT);
            });
            // Archive is also async; poll outside the Ops.run block.
            Await.untilTrue("gov account " + name + " to be archived",
                    () -> Ledger.governmentAccountIsArchived(name), TIMEOUT);
        }
    }

    @Test
    void accountCreateRejectsDuplicateNames() {
        String admin = Usernames.fresh("GdA");
        String name = freshAccountName("Dup");

        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + name);
                a.awaitMessageContaining("Created", TIMEOUT);

                a.drainMessages();
                a.chatCommand("/gov account create " + name);
                a.awaitMessageContaining("already exists", TIMEOUT);
            });
        }
    }

    @Test
    void accountListIncludesPrimitiveGovernmentAccounts() {
        String admin = Usernames.fresh("GlA");
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/gov account list");
                String response = a.awaitMessageContaining("DCGovernment", TIMEOUT);
                assertThat(response).contains("DCGovernment");
            });
        }
    }

    @Test
    void accountBalanceShowsTheCurrentMaterialisedBalance() {
        String admin = Usernames.fresh("GbA");
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/gov account balance DCGovernment");
                a.awaitMessageContaining("DCGovernment", TIMEOUT);
            });
        }
    }

    @Test
    void primitiveGovernmentAccountsCannotBeArchived() {
        String admin = Usernames.fresh("GpA");
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/gov account archive DCGovernment");
                a.awaitMessageContaining("Primitive", TIMEOUT);
            });
            assertThat(Ledger.governmentAccountIsArchived("DCGovernment")).isFalse();
        }
    }

    // --- transfer / pay / payout --------------------------------------------

    @Test
    void accountTransferMovesFundsBetweenGovernmentAccounts() {
        String admin = Usernames.fresh("GtA");
        String dst = freshAccountName("Dst");

        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + dst);
                a.awaitMessageContaining("Created", TIMEOUT);
                Ledger.waitForGovernmentAccount(dst, TIMEOUT);

                a.drainMessages();
                a.chatCommand("/gov account transfer DCGovernment " + dst + " 1000 seed");
                a.awaitMessageContaining("Transferred", TIMEOUT);
            });

            // Transfer is also @Async; poll for the materialised balance.
            Ledger.waitForGovernmentBalance(dst, new BigDecimal("1000.00"), TIMEOUT);
        }
    }

    @Test
    void accountTransferRejectsSameSourceAndDestination() {
        String admin = Usernames.fresh("GsA");
        String name = freshAccountName("Sm");

        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + name);
                a.awaitMessageContaining("Created", TIMEOUT);
                Ledger.waitForGovernmentAccount(name, TIMEOUT);

                Barrier.settle();
                a.drainMessages();
                a.chatCommand("/gov account transfer " + name + " " + name + " 10 self");
                a.awaitMessageContaining("same account", TIMEOUT);
            });
        }
    }

    @Test
    void payoutSendsFromGovernmentToPlayer() {
        String admin = Usernames.fresh("GpA");
        String recipient = Usernames.fresh("GpR");

        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer r = MockPlayer.connectLocal(recipient)) {
            BigDecimal start = Ledger.waitForPersonalBalance(r.getUuid(), TIMEOUT);

            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/gov payout DCGovernment " + recipient + " 250 stipend");
                a.awaitMessageContaining("Paid", TIMEOUT);
            });

            Ledger.waitForPersonalBalance(r.getUuid(),
                    start.add(new BigDecimal("250.00")), TIMEOUT);
        }
    }

    // --- member management --------------------------------------------------

    @Test
    void memberAddRemoveAndList() {
        String admin = Usernames.fresh("GmA");
        String memberName = Usernames.fresh("GmM");
        String account = freshAccountName("Mem");

        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer m = MockPlayer.connectLocal(memberName)) {
            Ledger.waitForPersonalBalance(m.getUuid(), TIMEOUT);

            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + account);
                a.awaitMessageContaining("Created", TIMEOUT);
                // Treasury commands are @Async — wait for the create txn
                // to commit before reading the account back.
                int accountId = Ledger.waitForGovernmentAccount(account, TIMEOUT);

                a.chatCommand("/gov account member add " + account + " " + memberName);
                a.awaitMessageContaining("Added", TIMEOUT);
                Ledger.waitForMemberCount(accountId, 1, TIMEOUT);

                a.drainMessages();
                a.chatCommand("/gov account member list " + account);
                a.awaitMessageContaining("Members of", TIMEOUT);

                a.drainMessages();
                a.chatCommand("/gov account member remove " + account + " " + memberName);
                a.awaitMessageContaining("Removed", TIMEOUT);
                Ledger.waitForMemberCount(accountId, 0, TIMEOUT);
            });
        }
    }

    // --- authorizer management ---------------------------------------------

    @Test
    void authMustBeMemberFirstThenAddRemoveCycle() {
        String admin = Usernames.fresh("GaA");
        String authName = Usernames.fresh("GaU");
        String account = freshAccountName("Aut");

        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer u = MockPlayer.connectLocal(authName)) {
            Ledger.waitForPersonalBalance(u.getUuid(), TIMEOUT);

            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + account);
                a.awaitMessageContaining("Created", TIMEOUT);
                int accountId = Ledger.waitForGovernmentAccount(account, TIMEOUT);

                // Adding an authorizer who isn't a member should fail
                // explicitly (rather than auto-promote silently).
                Barrier.settle();
                a.drainMessages();
                a.chatCommand("/gov account auth add " + account + " " + authName);
                a.awaitMessageContaining("must be a member", TIMEOUT);

                // Promote: add as member, then as authorizer.
                a.chatCommand("/gov account member add " + account + " " + authName);
                a.awaitMessageContaining("Added", TIMEOUT);
                Ledger.waitForMemberCount(accountId, 1, TIMEOUT);

                a.chatCommand("/gov account auth add " + account + " " + authName);
                a.awaitMessageContaining("Added", TIMEOUT);
                Ledger.waitForAuthorizerCount(accountId, 1, TIMEOUT);

                a.drainMessages();
                a.chatCommand("/gov account auth list " + account);
                a.awaitMessageContaining("Authorizers of", TIMEOUT);

                a.drainMessages();
                a.chatCommand("/gov account auth remove " + account + " " + authName);
                a.awaitMessageContaining("Removed", TIMEOUT);
                Ledger.waitForAuthorizerCount(accountId, 0, TIMEOUT);
            });
        }
    }

    // --- history ------------------------------------------------------------

    @Test
    void payoutSendsFromGovernmentToBusinessAccount() {
        // The non-player branch of doExternalPayout — toName resolves via
        // getBusinessAccountByName when knownPlayer is false. Asserts the
        // BUSINESS account credit landed and the gov account debited.
        String admin = Usernames.fresh("GpB");
        String firm = "GpBF" + Long.toHexString(Instant.now().toEpochMilli());
        String govAcct = freshAccountName("GpBgov");
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                // 1. Create firm + capture its BUSINESS account display name.
                a.chatCommand("/business create " + firm);
                a.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int firmAccountId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();

                // 2. Create + seed the gov source account.
                Barrier.settle();
                a.chatCommand("/gov account create " + govAcct);
                a.awaitMessageContaining("Created", TIMEOUT);
                Ledger.waitForGovernmentAccount(govAcct, TIMEOUT);
                Barrier.settle();
                a.chatCommand("/gov account transfer DCGovernment " + govAcct + " 500 seed");
                a.awaitMessageContaining("Transferred", TIMEOUT);
                Ledger.waitForGovernmentBalance(govAcct, new BigDecimal("500.00"), TIMEOUT);

                BigDecimal firmBefore = Ledger.accountBalanceById(firmAccountId)
                        .orElse(BigDecimal.ZERO);

                // 3. Pay the BUSINESS account. The <to> arg is word()-tokenised
                // so the full "FirmName Corporate Account" display name
                // can't fit in one token; doExternalPayout's fallback path
                // appends " Corporate Account" automatically so users only
                // have to type the firm name.
                Barrier.settle();
                a.drainMessages();
                a.chatCommand("/gov payout " + govAcct + " " + firm + " 200 corp stipend");
                a.awaitMessageContaining("Paid", TIMEOUT);

                // 4. Assert business credit + gov debit landed.
                Ledger.waitForAccountBalanceById(firmAccountId,
                        firmBefore.add(new BigDecimal("200.00")), TIMEOUT);
                assertThat(Ledger.governmentBalance(govAcct).orElseThrow())
                        .isEqualByComparingTo("300.00");
            });
        }
    }

    @Test
    void memberAddGroupAddsAndListShowsGroup() {
        // The "addgroup" variants take a LuckPerms group name instead of a
        // player UUID. Asserts group-add + the list route renders the
        // [group] tag.
        String admin = Usernames.fresh("Ggp");
        String account = freshAccountName("GgpAcc");
        String group = "tg" + Long.toHexString(Instant.now().toEpochMilli());
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            net.democracycraft.inttests.support.RconClient.send("lp creategroup " + group);
            try {
                Ops.run(admin, () -> {
                    a.chatCommand("/gov account create " + account);
                    a.awaitMessageContaining("Created", TIMEOUT);
                    Ledger.waitForGovernmentAccount(account, TIMEOUT);
                    Barrier.settle();

                    a.drainMessages();
                    a.chatCommand("/gov account member addgroup " + account + " " + group);
                    a.awaitMessageContaining("group", TIMEOUT);

                    Barrier.settle();
                    a.drainMessages();
                    a.chatCommand("/gov account member list " + account);
                    String r = a.awaitMessageContaining("[group]", TIMEOUT);
                    assertThat(r).contains(group);

                    Barrier.settle();
                    a.drainMessages();
                    a.chatCommand("/gov account member removegroup " + account + " " + group);
                    a.awaitMessageContaining("Removed", TIMEOUT);
                });
            } finally {
                net.democracycraft.inttests.support.RconClient.send("lp deletegroup " + group);
            }
        }
    }

    @Test
    void authAddGroupRejectsGroupNotMember() {
        // Group authorizer must be a group MEMBER first — mirrors the
        // per-UUID auth-not-member precondition.
        String admin = Usernames.fresh("Gga");
        String account = freshAccountName("GgaAcc");
        String group = "tag" + Long.toHexString(Instant.now().toEpochMilli());
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            net.democracycraft.inttests.support.RconClient.send("lp creategroup " + group);
            try {
                Ops.run(admin, () -> {
                    a.chatCommand("/gov account create " + account);
                    a.awaitMessageContaining("Created", TIMEOUT);
                    Ledger.waitForGovernmentAccount(account, TIMEOUT);
                    Barrier.settle();

                    a.drainMessages();
                    a.chatCommand("/gov account auth addgroup " + account + " " + group);
                    String r = a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                    Barrier.settle();
                    assertThat(r).containsIgnoringCase("group member");
                });
            } finally {
                net.democracycraft.inttests.support.RconClient.send("lp deletegroup " + group);
            }
        }
    }

    @Test
    void accountHistoryShowsRecentTransactions() {
        String admin = Usernames.fresh("GhA");
        String account = freshAccountName("Hist");

        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + account);
                a.awaitMessageContaining("Created", TIMEOUT);
                Ledger.waitForGovernmentAccount(account, TIMEOUT);

                Barrier.settle();
                a.chatCommand("/gov account transfer DCGovernment " + account + " 50 history seed");
                a.awaitMessageContaining("Transferred", TIMEOUT);

                Barrier.settle();
                a.drainMessages();
                a.chatCommand("/gov account history " + account);
                a.awaitMessageContaining(account, TIMEOUT);
            });
        }
    }
}
