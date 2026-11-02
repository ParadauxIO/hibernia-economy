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
        LedgerTxn txn = new LedgerTxn();
        txn.setPluginSystem("treasury-salary");
        txn.setMessage("unrelated text");
        assertThat(txn.getPayoutCategory()).isEqualTo(PayoutCategory.EMPLOYEE);
        TransactionEntry entry = new TransactionEntry();
        entry.setPluginSystem("realty");
        entry.setMemo("Rental Payment: employee-auction-house");
        assertThat(entry.getPayoutCategory()).isNull();
        entry.setPluginSystem("realty-auction");
        assertThat(entry.getPayoutCategory()).isEqualTo(PayoutCategory.AUCTION_PROCEEDS);
    }
}
