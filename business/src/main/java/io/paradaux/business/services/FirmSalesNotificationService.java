package io.paradaux.business.services;

import io.paradaux.treasury.api.market.ChestShopSaleRecord;

/**
 * Real-time firm sale notifications (PAR-179). Buffers ChestShop sales per firm
 * and flushes them on a timer as either a single line (low volume) or a
 * condensed digest (bursts), so a busy shop can't spam a firm's staff.
 */
public interface FirmSalesNotificationService {

    /** Buffer a completed sale for the next flush. No-op for non-firm shops. */
    void record(ChestShopSaleRecord sale);

    /**
     * Flush every buffered firm — sending one message each (single line or digest)
     * to firms that have notifications enabled. Runs on the main thread.
     */
    void flush();

    /** Whether real-time sale notifications are enabled for the firm. */
    boolean isEnabled(int firmId);

    /** Flip the per-firm enabled state; returns the new state. */
    boolean toggle(int firmId);
}
