package io.paradaux.business.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.config.FirmConfiguration;
import io.paradaux.business.services.FirmNotificationService;
import io.paradaux.business.services.FirmPlayerService;
import io.paradaux.business.services.FirmPropertyService;
import io.paradaux.business.services.FirmSalesNotificationService;
import io.paradaux.business.services.FirmService;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.api.market.ChestShopSaleRecord;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Buffers firm sales and flushes them as a single line or a condensed digest
 * (PAR-179). The per-firm enabled flag lives in {@code firm_properties} under
 * {@link #NOTIFY_KEY}. record()/flush() both run on the main thread (the event
 * is sync, the flush is a sync scheduler task), so the per-firm buffers need no
 * locking beyond the ConcurrentHashMap that holds them.
 */
@Singleton
public class FirmSalesNotificationServiceImpl implements FirmSalesNotificationService {

    static final String NOTIFY_KEY = "sales-notifications-enabled";
    private static final int DIGEST_ITEM_LIMIT = 3;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final FirmNotificationService notifications;
    private final FirmPropertyService properties;
    private final FirmService firms;
    private final FirmPlayerService players;
    private final TreasuryApi treasury;
    private final FirmConfiguration config;

    private final Map<Integer, FirmBuffer> buffers = new ConcurrentHashMap<>();

    @Inject
    public FirmSalesNotificationServiceImpl(FirmNotificationService notifications,
                                            FirmPropertyService properties, FirmService firms,
                                            FirmPlayerService players, TreasuryApi treasury,
                                            FirmConfiguration config) {
        this.notifications = notifications;
        this.properties = properties;
        this.firms = firms;
        this.players = players;
        this.treasury = treasury;
        this.config = config;
    }

    @Override
    public void record(ChestShopSaleRecord sale) {
        Integer firmId = sale.shopFirmId();
        if (firmId == null) {
            return; // not a firm-owned shop
        }
        // Buffer unconditionally (cheap, in-memory); the enabled check is done once
        // per firm at flush to keep the per-sale path off the DB.
        buffers.computeIfAbsent(firmId, k -> new FirmBuffer()).add(sale);
    }

    @Override
    public void flush() {
        if (buffers.isEmpty()) {
            return;
        }
        for (Integer firmId : new ArrayList<>(buffers.keySet())) {
            FirmBuffer buf = buffers.remove(firmId);
            if (buf == null || buf.count == 0) {
                continue;
            }
            if (!isEnabled(firmId)) {
                continue; // firm opted out — drop the buffered sales silently
            }
            send(firmId, buf);
        }
    }

    @Override
    public boolean isEnabled(int firmId) {
        return properties.getBoolean(firmId, NOTIFY_KEY).orElse(config.isSalesNotifyDefault());
    }

    @Override
    public boolean toggle(int firmId) {
        boolean next = !isEnabled(firmId);
        properties.setBoolean(firmId, NOTIFY_KEY, next);
        return next;
    }

    private void send(int firmId, FirmBuffer buf) {
        Firm firm = firms.getAnyFirmById(firmId);
        String firmName = firm != null ? firm.getDisplayName() : ("Firm #" + firmId);

        // Snapshot the buffer atomically (same monitor as add()) so building the
        // message can't race a concurrent record() if recordSale is ever invoked
        // off the main thread.
        long count;
        ChestShopSaleRecord first;
        LocalDateTime since;
        BigDecimal totalVolume;
        String items;
        synchronized (buf) {
            count = buf.count;
            first = buf.firstSale;
            since = buf.since;
            totalVolume = buf.totalVolume;
            items = buf.renderItems();
        }

        if (count == 1 && first != null) {
            notifications.notifyFirm(firmId, "business.sales.notify.single",
                    "firm", firmName,
                    "action", action(first.direction()),
                    "qty", first.quantity(),
                    "item", first.itemName(),
                    "customer", customerName(first.customerUuid()),
                    "total", treasury.formatAmount(first.totalPrice()));
            return;
        }

        notifications.notifyFirm(firmId, "business.sales.notify.digest",
                "firm", firmName,
                "count", count,
                "since", TIME_FMT.format(since),
                "items", items,
                "total", treasury.formatAmount(totalVolume));
    }

    /** Firm-centric label: a customer BUY means the firm sold, and vice versa. */
    private static String action(String direction) {
        return "BUY".equals(direction) ? "sold" : "bought";
    }

    private String customerName(UUID uuid) {
        if (uuid == null) {
            return "Unknown";
        }
        return players.findByUuid(uuid).map(p -> p.getCurrentName()).orElse("Unknown");
    }

    /** Per-firm accumulator: aggregates by (action, item) so the digest is compact. */
    private static final class FirmBuffer {
        private final LocalDateTime since = LocalDateTime.now();
        private final Map<String, ItemAgg> byItem = new LinkedHashMap<>();
        private long count;
        private BigDecimal totalVolume = BigDecimal.ZERO;
        private ChestShopSaleRecord firstSale;

        synchronized void add(ChestShopSaleRecord s) {
            if (count == 0) {
                firstSale = s;
            }
            count++;
            totalVolume = totalVolume.add(s.totalPrice() != null ? s.totalPrice() : BigDecimal.ZERO);
            String label = action(s.direction()) + " " + s.itemName();
            byItem.computeIfAbsent(label, k -> new ItemAgg(action(s.direction()), s.itemName()))
                    .add(s.quantity());
        }

        /** e.g. "sold 128x Cobblestone, sold 64x Iron Ingot (+2 more)". */
        String renderItems() {
            List<ItemAgg> aggs = new ArrayList<>(byItem.values());
            aggs.sort((a, b) -> Long.compare(b.qty, a.qty));
            StringBuilder sb = new StringBuilder();
            int shown = Math.min(DIGEST_ITEM_LIMIT, aggs.size());
            for (int i = 0; i < shown; i++) {
                ItemAgg a = aggs.get(i);
                if (i > 0) sb.append(", ");
                sb.append(a.action).append(' ').append(a.qty).append("x ").append(a.itemName);
            }
            if (aggs.size() > shown) {
                sb.append(" (+").append(aggs.size() - shown).append(" more)");
            }
            return sb.toString();
        }
    }

    private static final class ItemAgg {
        private final String action;
        private final String itemName;
        private long qty;

        ItemAgg(String action, String itemName) {
            this.action = action;
            this.itemName = itemName;
        }

        void add(long quantity) {
            qty += quantity;
        }
    }
}
