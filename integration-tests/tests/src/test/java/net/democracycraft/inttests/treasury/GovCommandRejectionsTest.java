package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * Negative-path coverage for /gov account transfer / payout / archive.
 * Validates: amount &lt;= 0, account-not-found, type-mismatch, insufficient,
 * unknown-recipient on payout.
 *
 * <p>Gov accounts are created via the same OP'd mock-player that drives the
 * tested command — the RCON CommandSender lacks {@code treasury.gov.*} grants
 * via LuckPerms, so console-side {@code gov account create} is rejected.
 */
class GovCommandRejectionsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** Issue {@code /gov account create} from a player already inside an Ops.run block. */
    private static void createGovAccount(MockPlayer issuer, String name) {
        issuer.drainMessages();
        issuer.chatCommand("/gov account create " + name);
        issuer.awaitMessageContaining("Created", TIMEOUT);
        Ledger.waitForGovernmentAccount(name, TIMEOUT);
        Barrier.settle();
    }

    @Test
    void transferRejectsZeroAmount() {
        String admin = Usernames.fresh("Gtz");
        String acctA = "GovZA" + Long.toHexString(System.nanoTime());
        String acctB = "GovZB" + Long.toHexString(System.nanoTime() + 1);
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                createGovAccount(a, acctA);
                createGovAccount(a, acctB);
                a.drainMessages();
                a.chatCommand("/gov account transfer " + acctA + " " + acctB + " 0 reason here");
                a.awaitMessageContaining("greater than zero", TIMEOUT);
            });
        }
    }

    @Test
    void transferRejectsNegativeAmount() {
        String admin = Usernames.fresh("Gtn");
        String acctA = "GovNA" + Long.toHexString(System.nanoTime());
        String acctB = "GovNB" + Long.toHexString(System.nanoTime() + 1);
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                createGovAccount(a, acctA);
                createGovAccount(a, acctB);
                a.drainMessages();
                a.chatCommand("/gov account transfer " + acctA + " " + acctB + " -100 r");
                a.awaitMessageContaining("greater than zero", TIMEOUT);
            });
        }
    }

    @Test
    void transferRejectsUnknownFromAccount() {
        String admin = Usernames.fresh("Gtu");
        String acctB = "GovU" + Long.toHexString(System.nanoTime());
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                createGovAccount(a, acctB);
                a.drainMessages();
                a.chatCommand("/gov account transfer DoesNotExistGov " + acctB + " 100 r");
                a.awaitMessageContaining("not found", TIMEOUT);
            });
        }
    }

    @Test
    void transferRejectsInsufficientBalance() {
        String admin = Usernames.fresh("Gti");
        String acctA = "GovIA" + Long.toHexString(System.nanoTime());
        String acctB = "GovIB" + Long.toHexString(System.nanoTime() + 1);
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                createGovAccount(a, acctA);
                createGovAccount(a, acctB);
                a.drainMessages();
                a.chatCommand("/gov account transfer " + acctA + " " + acctB + " 1000000 r");
                a.awaitMessageContaining("Insufficient funds", TIMEOUT);
            });
        }
    }

    @Test
    void payoutRejectsZeroAmount() {
        String admin = Usernames.fresh("Gpz");
        String src = "PayZ" + Long.toHexString(System.nanoTime());
        String target = Usernames.fresh("Gpzt");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                createGovAccount(a, src);
                a.drainMessages();
                a.chatCommand("/gov payout " + src + " " + target + " 0 r");
                a.awaitMessageContaining("greater than zero", TIMEOUT);
            });
        }
    }

    @Test
    void payoutRejectsUnknownRecipient() {
        String admin = Usernames.fresh("Gpu");
        String src = "PayU" + Long.toHexString(System.nanoTime());
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                createGovAccount(a, src);
                a.drainMessages();
                a.chatCommand("/gov payout " + src + " GhostRecipient 50 r");
                a.awaitMessageContaining("Unknown recipient", TIMEOUT);
            });
        }
    }

    @Test
    void accountArchiveRejectsUnknownAccount() {
        String admin = Usernames.fresh("Gau");
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/gov account archive DefinitelyDoesNotExist");
                a.awaitMessageContaining("not found", TIMEOUT);
            });
        }
    }

    @Test
    void transferRejectsUnknownToAccount() {
        // Mirror of transferRejectsUnknownFromAccount but for the destination
        // side — resolveGovAccount(toName) returns null and the rejection
        // surfaces as `treasury.gov.not-found`.
        String admin = Usernames.fresh("Gtu2");
        String acctA = "GovUA" + Long.toHexString(System.nanoTime());
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                createGovAccount(a, acctA);
                a.drainMessages();
                a.chatCommand("/gov account transfer " + acctA + " DoesNotExistGov 100 r");
                a.awaitMessageContaining("not found", TIMEOUT);
            });
        }
    }

    @Test
    void payoutRejectsUnknownFromAccount() {
        String admin = Usernames.fresh("Gpf");
        String target = Usernames.fresh("Gpft");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/gov payout DefinitelyMissingSrc " + target + " 100 r");
                a.awaitMessageContaining("not found", TIMEOUT);
            });
        }
    }

    @Test
    void payoutRejectsInsufficientBalanceOnFreshAccount() {
        // A freshly-created gov account has balance 0; paying out anything must
        // surface the insufficient-funds branch (LedgerService throws IAE → the
        // payout handler routes to treasury.gov.account.transfer.insufficient).
        String admin = Usernames.fresh("Gpi");
        String src = "PayI" + Long.toHexString(System.nanoTime());
        String target = Usernames.fresh("Gpit");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                createGovAccount(a, src);
                a.drainMessages();
                a.chatCommand("/gov payout " + src + " " + target + " 100 r");
                a.awaitMessageContaining("Insufficient funds", TIMEOUT);
            });
        }
    }

    @Test
    void transferRejectsSubCentAmount() {
        // Defence-in-depth: with hibernia's BigDecimalArgumentType the
        // sub-cent amount reaches the resolver; Money.requirePositive throws
        // and the handler emits the invalid-amount branch — *not* a silent
        // round to zero.
        String admin = Usernames.fresh("Gts");
        String acctA = "GovSA" + Long.toHexString(System.nanoTime());
        String acctB = "GovSB" + Long.toHexString(System.nanoTime() + 1);
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                createGovAccount(a, acctA);
                createGovAccount(a, acctB);
                a.drainMessages();
                a.chatCommand("/gov account transfer " + acctA + " " + acctB + " 0.001 r");
                a.awaitMessageContaining("greater than zero", TIMEOUT);
            });
        }
    }

    @Test
    void memberAddRejectsUnknownAccount() {
        // The unknown-account branch is reached via resolveGovAccount
        // returning null; surface message is `treasury.gov.not-found`.
        String admin = Usernames.fresh("Gmu");
        String target = Usernames.fresh("Gmt");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/gov account member add NeverExisted " + target);
                a.awaitMessageContaining("not found", TIMEOUT);
            });
        }
    }

    @Test
    void authAddRejectsUnknownAccount() {
        String admin = Usernames.fresh("Gau2");
        String target = Usernames.fresh("Gau2t");
        try (MockPlayer a = MockPlayer.connectLocal(admin);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/gov account auth add NeverExistedAuth " + target);
                a.awaitMessageContaining("not found", TIMEOUT);
            });
        }
    }

    @Test
    void historyRejectsUnknownAccount() {
        String admin = Usernames.fresh("Ghu");
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/gov account history NeverExisted");
                a.awaitMessageContaining("not found", TIMEOUT);
            });
        }
    }

    @Test
    void balanceRejectsUnknownAccount() {
        String admin = Usernames.fresh("Gbu");
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.drainMessages();
                a.chatCommand("/gov account balance NeverExisted");
                a.awaitMessageContaining("not found", TIMEOUT);
            });
        }
    }
}
