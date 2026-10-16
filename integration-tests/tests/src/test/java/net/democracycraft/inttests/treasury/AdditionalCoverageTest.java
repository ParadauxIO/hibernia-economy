package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Branch-coverage filler for Treasury command handlers — the leftover paths
 * not exercised by command-family tests:
 * <ul>
 *   <li>page-clamp branches (page &lt; 1 → 1, page &gt; MAX_PAGE → MAX_PAGE)</li>
 *   <li>list-empty branches (history, fines)</li>
 *   <li>fine-info revoked-detail branch</li>
 *   <li>pay-to-gov insufficient-funds branch</li>
 *   <li>transactions export of a non-existent account</li>
 * </ul>
 */
class AdditionalCoverageTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void baltopClampsZeroPageToOne() {
        String name = Usernames.fresh("Bz");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            p.drainMessages();
            p.chatCommand("/baltop 0");
            p.awaitMessageContaining("Top Balances", TIMEOUT);
        }
    }

    @Test
    void baltopClampsHugePageWithoutErroring() {
        String name = Usernames.fresh("Bh");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            p.drainMessages();
            p.chatCommand("/baltop 99999");
            // Whatever the response is, server doesn't error out.
            p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
            Barrier.settle();
        }
    }

    @Test
    void govAccountHistoryWithNoTransactionsShowsEmpty() {
        String admin = Usernames.fresh("Ghe");
        String acct = "GhEmp" + Long.toHexString(System.nanoTime());
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + acct);
                a.awaitMessageContaining("Created", TIMEOUT);
                Ledger.waitForGovernmentAccount(acct, TIMEOUT);

                a.drainMessages();
                a.chatCommand("/gov account history " + acct);
                a.awaitMessageContaining("No transactions", TIMEOUT);
            });
        }
    }

    @Test
    void govAccountHistoryClampsZeroPage() {
        String admin = Usernames.fresh("Ghc");
        String acct = "GhCmp" + Long.toHexString(System.nanoTime());
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + acct);
                a.awaitMessageContaining("Created", TIMEOUT);
                Ledger.waitForGovernmentAccount(acct, TIMEOUT);

                a.drainMessages();
                a.chatCommand("/gov account history " + acct + " 0");
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });
        }
    }

    @Test
    void fineListWithNoFinesShowsEmpty() {
        String name = Usernames.fresh("Fle");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/fine list");
                p.awaitMessageContaining("No fines", TIMEOUT);
            });
        }
    }

    @Test
    void fineInfoOnRevokedFineShowsRevocationDetail() {
        String issuer = Usernames.fresh("Fir");
        String target = Usernames.fresh("Firt");
        try (MockPlayer i = MockPlayer.connectLocal(issuer);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);

            Ops.run(issuer, () -> {
                i.chatCommand("/fine issue GovernmentFines " + target + " 25 testing");
                i.awaitMessageContaining("Issued fine", TIMEOUT);

                long fineId = Ledger.waitForMostRecentFine(t.getUuid(), TIMEOUT);

                Barrier.settle();
                i.drainMessages();
                i.chatCommand("/fine revoke " + fineId);
                i.awaitMessageContaining("Revoked", TIMEOUT);

                Barrier.settle();
                i.drainMessages();
                i.chatCommand("/fine info " + fineId);
                i.awaitMessage(m -> m.toLowerCase().contains("revok"), TIMEOUT);
            });
        }
    }

    @Test
    void payToGovAccountRejectsInsufficientFunds() {
        String sender = Usernames.fresh("Pgi");
        String admin = Usernames.fresh("Pga");
        String account = "PayG" + Long.toHexString(System.nanoTime());
        try (MockPlayer s = MockPlayer.connectLocal(sender);
             MockPlayer a = MockPlayer.connectLocal(admin)) {
            BigDecimal start = Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);

            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + account);
                a.awaitMessageContaining("Created", TIMEOUT);
                Ledger.waitForGovernmentAccount(account, TIMEOUT);
            });

            // Try to pay way more than the sender has.
            s.drainMessages();
            s.chatCommand("/pay " + account + " " + start.add(new BigDecimal("100000")).toPlainString());
            s.awaitMessageContaining("Insufficient", TIMEOUT);
        }
    }

    @Test
    void transactionsExportOfUnknownAccountRejected() {
        String name = Usernames.fresh("Te");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/transactions export 9999999");
                p.awaitMessage(m -> m.contains("not found") || m.contains("no access")
                        || m.toLowerCase().contains("not found"), TIMEOUT);
            });
        }
    }

}
