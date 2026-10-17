package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.support.ServerTest;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;
import static io.paradaux.chestshop.model.Transaction.TransactionType.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the rolling per-window buy/sell counters. Real {@link ItemStack}s (MockBukkit)
 * carry the amounts; the window rollover is forced by rewinding the private {@code lastReset}.
 */
class MetricsServiceImplTest extends ServerTest {

    private MetricsServiceImpl metrics;

    @BeforeEach
    void setUp() {
        metrics = new MetricsServiceImpl();
    }

    private Transaction txn(Transaction.TransactionType type, ItemStack... stock) {
        Transaction t = mock(Transaction.class);
        when(t.getTransactionType()).thenReturn(type);
        when(t.getStock()).thenReturn(stock);
        return t;
    }

    @Test
    void freshWindow_reportsCurrentCounters() {
        // Last == -1 for every counter, so the getters return the (zero) current values.
        assertThat(metrics.getBuyTransactions()).isZero();
        assertThat(metrics.getSellTransactions()).isZero();
        assertThat(metrics.getBoughtItemsCount()).isZero();
        assertThat(metrics.getSoldItemsCount()).isZero();
        assertThat(metrics.getTotalTransactions()).isZero();
        assertThat(metrics.getTotalItemsCount()).isZero();
    }

    @Test
    void accumulatesBuyAndSellWithinTheWindow() {
        metrics.onTransaction(txn(BUY, item(Material.DIAMOND, 32), item(Material.DIAMOND, 8)));
        metrics.onTransaction(txn(BUY, item(Material.DIAMOND, 1)));
        metrics.onTransaction(txn(SELL, item(Material.STONE, 5)));

        assertThat(metrics.getBuyTransactions()).isEqualTo(2);
        assertThat(metrics.getSellTransactions()).isEqualTo(1);
        assertThat(metrics.getTotalTransactions()).isEqualTo(3);
        assertThat(metrics.getBoughtItemsCount()).isEqualTo(41);
        assertThat(metrics.getSoldItemsCount()).isEqualTo(5);
        assertThat(metrics.getTotalItemsCount()).isEqualTo(46);
    }

    @Test
    void windowRollover_snapshotsCurrentIntoTheLastValues() throws Exception {
        metrics.onTransaction(txn(BUY, item(Material.DIAMOND, 10)));
        metrics.onTransaction(txn(SELL, item(Material.STONE, 3)));

        // Rewind the window start so the next call trips checkReset()'s elapsed branch.
        Field lastReset = MetricsServiceImpl.class.getDeclaredField("lastReset");
        lastReset.setAccessible(true);
        lastReset.setLong(metrics, 0L);

        // First read after the rollover snapshots the completed window; getters now return "last".
        assertThat(metrics.getBuyTransactions()).isEqualTo(1);
        assertThat(metrics.getSellTransactions()).isEqualTo(1);
        assertThat(metrics.getBoughtItemsCount()).isEqualTo(10);
        assertThat(metrics.getSoldItemsCount()).isEqualTo(3);
        assertThat(metrics.getTotalTransactions()).isEqualTo(2);
        assertThat(metrics.getTotalItemsCount()).isEqualTo(13);

        // New activity accrues into the fresh window but the reported (completed-window) values hold.
        metrics.onTransaction(txn(BUY, item(Material.DIAMOND, 100)));
        assertThat(metrics.getBuyTransactions()).isEqualTo(1);
        assertThat(metrics.getBoughtItemsCount()).isEqualTo(10);
    }
}
