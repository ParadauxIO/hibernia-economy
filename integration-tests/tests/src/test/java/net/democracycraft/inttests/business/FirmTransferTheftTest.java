package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Perms;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the firm-theft hole: a holder of the (broadly-granted)
 * business.transfer.* nodes could take over any firm with no began/confirmed
 * transfer. Money access follows proprietorship (member+authorizer on every
 * firm account), so this was a direct drain.
 *
 * <p>The attacker is granted business.* via LuckPerms (no op) to hold the
 * exploit permission realistically, without the stale-Brigadier churn of a
 * second Ops.run.
 */
class FirmTransferTheftTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private static String firmName(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @Test
    void unconfirmedTransferCompleteCannotStealFirm() {
        String owner = Usernames.fresh("FtO");
        String attacker = Usernames.fresh("FtA");
        String firm = firmName("Ftf");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer a = MockPlayer.connectLocal(attacker)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);

            final int[] firmIdH = new int[1];
            Ops.run(owner, () -> {
                o.chatCommand("/business create " + firm);
                o.awaitMessageContaining("Successfully created", TIMEOUT);
                firmIdH[0] = BusinessLedger.waitForFirm(firm, TIMEOUT);
            });
            int firmId = firmIdH[0];
            assertThat(BusinessLedger.firmProprietor(firmId)).contains(o.getUuid());

            Perms.grant(attacker, "business.*");
            try {
                // No transfer was ever begun or confirmed.
                a.drainMessages();
                a.chatCommand("/business transfer complete " + firm + " " + attacker);
                Barrier.settle();
                assertReachedBusiness(a);

                assertThat(BusinessLedger.firmProprietor(firmId))
                        .as("a transfer with no CONFIRMED request must NOT change the proprietor")
                        .contains(o.getUuid());
            } finally {
                Perms.revoke(attacker, "business.*");
            }
        }
    }

    @Test
    void nonProprietorCannotBeginTransfer() {
        String owner = Usernames.fresh("FbO");
        String attacker = Usernames.fresh("FbA");
        String victim = Usernames.fresh("FbV");
        String firm = firmName("Fbf");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer a = MockPlayer.connectLocal(attacker);
             MockPlayer v = MockPlayer.connectLocal(victim)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(v.getUuid(), TIMEOUT);

            final int[] firmIdH = new int[1];
            Ops.run(owner, () -> {
                o.chatCommand("/business create " + firm);
                o.awaitMessageContaining("Successfully created", TIMEOUT);
                firmIdH[0] = BusinessLedger.waitForFirm(firm, TIMEOUT);
            });
            int firmId = firmIdH[0];

            Perms.grant(attacker, "business.*");
            try {
                // Attacker is NOT the proprietor of `firm`.
                a.drainMessages();
                a.chatCommand("/business transfer begin " + firm + " " + victim);
                Barrier.settle();
                assertReachedBusiness(a);

                assertThat(BusinessLedger.activeTransferToken(firmId, v.getUuid()))
                        .as("a non-proprietor must not be able to create a transfer request")
                        .isEmpty();
            } finally {
                Perms.revoke(attacker, "business.*");
            }
        }
    }

    /**
     * The attack must have been judged by Business, not bounced by the permission gate as
     * an unknown command — otherwise "nothing changed" proves nothing. After
     * {@link Barrier#settle} the handler's reply is already buffered.
     */
    private static void assertReachedBusiness(MockPlayer attacker) {
        String reply = attacker.awaitMessage(m -> !m.isBlank(), Duration.ofSeconds(1));
        assertThat(reply).as("the attack command's reply").doesNotContainIgnoringCase("unknown");
    }
}
