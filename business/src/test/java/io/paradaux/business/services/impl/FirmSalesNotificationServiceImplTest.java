package io.paradaux.business.services.impl;

import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmPlayer;
import io.paradaux.business.model.config.FirmConfiguration;
import io.paradaux.business.services.FirmNotificationService;
import io.paradaux.business.services.FirmPlayerService;
import io.paradaux.business.services.FirmPropertyService;
import io.paradaux.business.services.FirmService;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.api.market.ChestShopSaleRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirmSalesNotificationServiceImplTest {

    @Mock FirmNotificationService notifications;
    @Mock FirmPropertyService properties;
    @Mock FirmService firms;
    @Mock FirmPlayerService players;
    @Mock TreasuryApi treasury;
    @Mock FirmConfiguration config;

    private FirmSalesNotificationServiceImpl svc;

    @BeforeEach
    void setUp() {
        svc = new FirmSalesNotificationServiceImpl(notifications, properties, firms, players, treasury, config);
        lenient().when(treasury.formatAmount(any())).thenAnswer(i -> "$" + i.getArgument(0));
        lenient().when(firms.getAnyFirmByNameOrId("7")).thenReturn(firm(7, "Costco"));
    }

    @Test
    void singleSale_sendsSingleLine() {
        when(properties.getBoolean(7, FirmSalesNotificationServiceImpl.NOTIFY_KEY)).thenReturn(Optional.of(true));
        when(players.findByUuid(any(UUID.class))).thenReturn(Optional.of(firmPlayer("Steve")));

        svc.record(sale(7, "BUY", "Cobblestone", 64, "128.00"));
        svc.flush();

        verify(notifications).notifyFirm(eq(7), eq("business.sales.notify.single"), any(Object[].class));
    }

    @Test
    void burst_condensesIntoDigestAggregatedByItem() {
        when(properties.getBoolean(7, FirmSalesNotificationServiceImpl.NOTIFY_KEY)).thenReturn(Optional.of(true));

        svc.record(sale(7, "BUY", "Cobblestone", 64, "64.00"));
        svc.record(sale(7, "BUY", "Cobblestone", 64, "64.00"));
        svc.record(sale(7, "BUY", "Iron Ingot", 10, "100.00"));
        svc.flush();

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(notifications).notifyFirm(eq(7), eq("business.sales.notify.digest"), captor.capture());

        // Placeholders are flattened key,value pairs; find the ones we care about.
        Object[] ph = captor.getValue();
        assertThat(placeholder(ph, "count")).isEqualTo(3L);
        assertThat(placeholder(ph, "total")).isEqualTo("$228.00");          // 64 + 64 + 100
        // Cobblestone aggregated to 128 and listed before Iron (higher qty).
        assertThat(String.valueOf(placeholder(ph, "items")))
                .contains("sold 128x Cobblestone")
                .contains("sold 10x Iron Ingot");
    }

    @Test
    void nonFirmSale_isIgnored() {
        svc.record(sale(null, "BUY", "Dirt", 1, "1.00")); // personal/admin shop
        svc.flush();
        verifyNoInteractions(notifications);
    }

    @Test
    void disabledFirm_buffersButSendsNothing() {
        when(properties.getBoolean(7, FirmSalesNotificationServiceImpl.NOTIFY_KEY)).thenReturn(Optional.of(false));
        svc.record(sale(7, "BUY", "Cobblestone", 64, "64.00"));
        svc.flush();
        verify(notifications, never()).notifyFirm(anyInt(), any(), any());
    }

    @Test
    void toggle_flipsAndPersists() {
        when(properties.getBoolean(7, FirmSalesNotificationServiceImpl.NOTIFY_KEY)).thenReturn(Optional.of(false));
        assertThat(svc.toggle(7)).isTrue();
        verify(properties).setBoolean(7, FirmSalesNotificationServiceImpl.NOTIFY_KEY, true);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ChestShopSaleRecord sale(Integer firmId, String direction, String item, int qty, String total) {
        return new ChestShopSaleRecord(null, direction, UUID.randomUUID(), 700, "BUSINESS", firmId, null, false,
                item, item, item, false, null,
                qty, new BigDecimal("1.00"), new BigDecimal(total), BigDecimal.ZERO, "world", 1, 1, 1, 0);
    }

    private static Firm firm(int id, String name) {
        Firm f = new Firm();
        f.setFirmId(id);
        f.setDisplayName(name);
        return f;
    }

    private static FirmPlayer firmPlayer(String name) {
        FirmPlayer p = new FirmPlayer();
        p.setCurrentName(name);
        return p;
    }

    private static Object placeholder(Object[] flat, String key) {
        for (int i = 0; i + 1 < flat.length; i += 2) {
            if (key.equals(flat[i])) return flat[i + 1];
        }
        return null;
    }
}
