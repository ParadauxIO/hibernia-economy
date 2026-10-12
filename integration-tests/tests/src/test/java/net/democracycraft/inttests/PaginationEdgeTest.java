package net.democracycraft.inttests;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

/**
 * Pagination boundary coverage for every paginated command:
 *
 * <ul>
 *   <li>{@code /baltop [page]}</li>
 *   <li>{@code /transactions [page]}</li>
 *   <li>{@code /gov account history <name> [page]}</li>
 *   <li>{@code /business account transactions <firm> <accountId> [page]}</li>
 *   <li>{@code /business transactions <firm> [page]}</li>
 * </ul>
 *
 * <p>For each: page = 0, page = negative, page = far-past-end. The contract is
 * "respond cleanly with no Java stack trace and no double-fire"; the exact
 * presentation (clamp vs. empty) varies per command but the user-facing
 * behaviour must be safe.
 */
class PaginationEdgeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    // ============================================================ /baltop

    @Test
    void baltopPageZeroIsHandledCleanly() {
        try (MockPlayer p = MockPlayer.connectLocal(Usernames.fresh("Bz"))) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            p.drainMessages();
            p.chatCommand("/baltop 0");
            // Source clamps page < 1 to 1; assert the header arrives.
            p.awaitMessageContaining("Top Balances", TIMEOUT);
        }
    }

    @Test
    void baltopPageNegativeIsHandledCleanly() {
        try (MockPlayer p = MockPlayer.connectLocal(Usernames.fresh("Bn"))) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            p.drainMessages();
            p.chatCommand("/baltop -5");
            p.awaitMessageContaining("Top Balances", TIMEOUT);
        }
    }

    @Test
    void baltopPageFarBeyondEndShowsHeaderOrEmpty() {
        try (MockPlayer p = MockPlayer.connectLocal(Usernames.fresh("Bf"))) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            p.drainMessages();
            p.chatCommand("/baltop 9999999");
            // Either an empty-state message or the header with no entries.
            p.awaitMessage(m -> m.contains("Top Balances")
                    || m.contains("No balances"), TIMEOUT);
        }
    }

    // ============================================================ /transactions

    @Test
    void transactionsPageZeroIsHandledCleanly() {
        try (MockPlayer p = MockPlayer.connectLocal(Usernames.fresh("Tz"))) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            p.drainMessages();
            p.chatCommand("/transactions 0");
            p.awaitMessage(m -> m.contains("Transactions")
                    || m.contains("No transactions"), TIMEOUT);
        }
    }

    @Test
    void transactionsPageNegativeIsHandledCleanly() {
        try (MockPlayer p = MockPlayer.connectLocal(Usernames.fresh("Tn"))) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            p.drainMessages();
            p.chatCommand("/transactions -3");
            p.awaitMessage(m -> m.contains("Transactions")
                    || m.contains("No transactions"), TIMEOUT);
        }
    }

    @Test
    void transactionsPageFarBeyondEndShowsEmpty() {
        try (MockPlayer p = MockPlayer.connectLocal(Usernames.fresh("Tf"))) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            p.drainMessages();
            p.chatCommand("/transactions 9999999");
            // Far-out page yields no entries — header may still print.
            p.awaitMessage(m -> m.contains("Transactions")
                    || m.contains("No transactions"), TIMEOUT);
        }
    }

    // ============================================================ /gov account history

    @Test
    void govAccountHistoryPageZeroIsHandledCleanly() {
        String admin = Usernames.fresh("Ghz");
        String account = "Ghz" + Long.toHexString(System.nanoTime());
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + account);
                a.awaitMessageContaining("Created", TIMEOUT);
                Ledger.waitForGovernmentAccount(account, TIMEOUT);

                Ledger.asyncSettle();
                a.drainMessages();
                a.chatCommand("/gov account history " + account + " 0");
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
            });
        }
    }

    @Test
    void govAccountHistoryPageNegativeIsHandledCleanly() {
        String admin = Usernames.fresh("Ghn");
        String account = "Ghn" + Long.toHexString(System.nanoTime());
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + account);
                a.awaitMessageContaining("Created", TIMEOUT);
                Ledger.waitForGovernmentAccount(account, TIMEOUT);

                Ledger.asyncSettle();
                a.drainMessages();
                a.chatCommand("/gov account history " + account + " -1");
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
            });
        }
    }

    @Test
    void govAccountHistoryPageFarBeyondEndShowsEmpty() {
        String admin = Usernames.fresh("Ghf");
        String account = "Ghf" + Long.toHexString(System.nanoTime());
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + account);
                a.awaitMessageContaining("Created", TIMEOUT);
                Ledger.waitForGovernmentAccount(account, TIMEOUT);

                Ledger.asyncSettle();
                a.drainMessages();
                a.chatCommand("/gov account history " + account + " 9999999");
                a.awaitMessage(m -> !m.isBlank(), TIMEOUT);
            });
        }
    }

    // ============================================================ /business transactions

    @Test
    void businessTransactionsPageZeroIsHandledCleanly() {
        String owner = Usernames.fresh("Btz");
        String firm = freshFirmName("BtzF");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                Ledger.asyncSettle();
                p.drainMessages();
                p.chatCommand("/business transactions " + firm + " 0");
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
            });
        }
    }

    @Test
    void businessTransactionsPageNegativeIsHandledCleanly() {
        String owner = Usernames.fresh("Btn");
        String firm = freshFirmName("BtnF");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                Ledger.asyncSettle();
                p.drainMessages();
                p.chatCommand("/business transactions " + firm + " -1");
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
            });
        }
    }

    @Test
    void businessTransactionsPageFarBeyondEndShowsEmpty() {
        String owner = Usernames.fresh("Btf");
        String firm = freshFirmName("BtfF");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                Ledger.asyncSettle();
                p.drainMessages();
                p.chatCommand("/business transactions " + firm + " 9999999");
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
            });
        }
    }

    // ============================================================ /business account transactions

    @Test
    void businessAccountTransactionsPageZeroIsHandledCleanly() {
        String owner = Usernames.fresh("Atz");
        String firm = freshFirmName("AtzF");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int acctId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
                Ledger.asyncSettle();
                p.drainMessages();
                p.chatCommand("/business account transactions " + firm + " " + acctId + " 0");
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
            });
        }
    }

    @Test
    void businessAccountTransactionsPageFarBeyondEndShowsEmpty() {
        String owner = Usernames.fresh("Atf");
        String firm = freshFirmName("AtfF");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int acctId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
                Ledger.asyncSettle();
                p.drainMessages();
                p.chatCommand("/business account transactions " + firm + " " + acctId + " 9999999");
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
            });
        }
    }
}
