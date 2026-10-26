package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Treasury's {@code /transactions} (alias {@code /txns}) command.
 *
 * <ul>
 *   <li>{@code /transactions} / {@code /transactions &lt;page&gt;} — own paged history</li>
 *   <li>{@code /transactions export} — bytebin URL of own CSV history</li>
 * </ul>
 */
class TransactionsCommandTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void ownTransactionsListShowsRecentLedgerEntries() {
        String username = Usernames.fresh("Tx");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            BigDecimal start = Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            // Generate at least one ledger entry beyond the join-seed by
            // having an admin debit them.
            RconClient.send("eco take " + username + " 100");
            Ledger.waitForPersonalBalance(p.getUuid(),
                    start.subtract(new BigDecimal("100.00")), TIMEOUT);

            // Confirm at least 2 postings exist for this account.
            int accountId = Ledger.personalAccountId(p.getUuid()).orElseThrow();
            assertThat(Ledger.postingsCountForAccount(accountId)).isGreaterThanOrEqualTo(2);

            p.drainMessages();
            p.chatCommand("/transactions");
            String header = p.awaitMessageContaining("Transactions", TIMEOUT);
            assertThat(header).contains("Page");
        }
    }

    @Test
    void transactionsExportProducesABytebinUrl() {
        String username = Usernames.fresh("TxE");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            // /transactions export is gated on treasury.transactions.export
            // which is op-only by default.
            Ops.run(username, () -> {
                p.drainMessages();
                p.chatCommand("/transactions export");
                String response = p.awaitMessageContaining("exported", Duration.ofSeconds(10));
                assertThat(response).containsAnyOf("http://", "https://");
            });
        }
    }
}
