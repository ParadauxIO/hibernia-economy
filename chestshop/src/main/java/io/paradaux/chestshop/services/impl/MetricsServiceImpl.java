package io.paradaux.chestshop.services.impl;

import com.google.inject.Singleton;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.services.MetricsService;
import io.paradaux.chestshop.utils.NumberUtil;
import org.bukkit.inventory.ItemStack;

/**
 * Rolling buy/sell transaction + item counters for {@code /csmetrics}, kept per-window: the
 * current window accumulates until {@link #RESET_MINUTES} elapse, then it snapshots to the
 * "last" values and starts fresh (so the command reports a stable completed window). Was the
 * static {@code MetricsModule} (PAR-316).
 */
@Singleton
public class MetricsServiceImpl implements MetricsService {

    private static final long RESET_MINUTES = 30;

    private long lastReset = System.currentTimeMillis();

    private int buyTransactionsLast = -1;
    private int sellTransactionsLast = -1;
    private long buyTransactionsCurrent = 0;
    private long sellTransactionsCurrent = 0;

    private int boughtItemsLast = -1;
    private int soldItemsLast = -1;
    private long boughtItemsCurrent = 0;
    private long soldItemsCurrent = 0;

    @Override
    public synchronized void onTransaction(Transaction event) {
        checkReset();
        switch (event.getTransactionType()) {
            case BUY:
                buyTransactionsCurrent++;
                for (ItemStack itemStack : event.getStock()) {
                    boughtItemsCurrent += itemStack.getAmount();
                }
                break;
            case SELL:
                sellTransactionsCurrent++;
                for (ItemStack itemStack : event.getStock()) {
                    soldItemsCurrent += itemStack.getAmount();
                }
                break;
        }
    }

    @Override
    public synchronized int getBuyTransactions() {
        checkReset();
        return buyTransactionsLast > -1 ? buyTransactionsLast : NumberUtil.toInt(buyTransactionsCurrent);
    }

    @Override
    public synchronized int getSellTransactions() {
        checkReset();
        return sellTransactionsLast > -1 ? sellTransactionsLast : NumberUtil.toInt(sellTransactionsCurrent);
    }

    @Override
    public synchronized int getTotalTransactions() {
        return getBuyTransactions() + getSellTransactions();
    }

    @Override
    public synchronized int getBoughtItemsCount() {
        checkReset();
        return boughtItemsLast > -1 ? boughtItemsLast : NumberUtil.toInt(boughtItemsCurrent);
    }

    @Override
    public synchronized int getSoldItemsCount() {
        checkReset();
        return soldItemsLast > -1 ? soldItemsLast : NumberUtil.toInt(soldItemsCurrent);
    }

    @Override
    public synchronized int getTotalItemsCount() {
        return getBoughtItemsCount() + getSoldItemsCount();
    }

    private void checkReset() {
        if (lastReset + RESET_MINUTES * 60 * 1000 < System.currentTimeMillis()) {
            lastReset = System.currentTimeMillis();
            buyTransactionsLast = NumberUtil.toInt(buyTransactionsCurrent);
            buyTransactionsCurrent = 0;
            sellTransactionsLast = NumberUtil.toInt(sellTransactionsCurrent);
            sellTransactionsCurrent = 0;

            boughtItemsLast = NumberUtil.toInt(boughtItemsCurrent);
            boughtItemsCurrent = 0;
            soldItemsLast = NumberUtil.toInt(soldItemsCurrent);
            soldItemsCurrent = 0;
        }
    }
}
