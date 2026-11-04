package io.paradaux.treasury.model.economy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PayoutCategoryTest {
    @Test
    void dedicatedSourcesAreCaseInsensitiveAndMixedPurposeSourcesStayUnknown() {
        assertThat(PayoutCategory.fromSource("treasury-salary")).isEqualTo(PayoutCategory.EMPLOYEE);
        assertThat(PayoutCategory.fromSource("REALTY-AUCTION")).isEqualTo(PayoutCategory.AUCTION_PROCEEDS);
        for (String source : new String[] {
            null, "", "realty", "BusinessPlugin", "nightcore", "Exams", "rest-api", "Employee", "auction"
        }) {
            assertThat(PayoutCategory.fromSource(source))
                    .as("source %s", source)
                    .isNull();
        }
    }

    @Test
    void oldLedgerRowsDerivePurposeWithoutNewColumnsOrMemoParsing() {
        LedgerTxn txn = LedgerTxn.builder()
                .pluginSystem("treasury-salary")
                .message("unrelated text")
                .build();
        assertThat(txn.getPayoutCategory()).isEqualTo(PayoutCategory.EMPLOYEE);
        TransactionEntry entry = TransactionEntry.builder()
                .pluginSystem("realty")
                .memo("Rental Payment: employee-auction-house")
                .build();
        assertThat(entry.getPayoutCategory()).isNull();
        entry = entry.withPluginSystem("realty-auction");
        assertThat(entry.getPayoutCategory()).isEqualTo(PayoutCategory.AUCTION_PROCEEDS);
    }
}
