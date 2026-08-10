package io.paradaux.treasury.api.impl;

import io.paradaux.treasury.api.market.ChestShopSaleRecord;
import io.paradaux.treasury.api.market.ChestShopShopRecord;
import io.paradaux.treasury.mappers.ChestShopMarketMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MarketApiImplTest {

    @Mock
    ChestShopMarketMapper mapper;

    @SuppressWarnings("unchecked")
    @Test
    void recordSale_buildsParamMapAndInserts() {
        MarketApiImpl api = new MarketApiImpl(mapper);
        UUID customer = UUID.randomUUID();
        ChestShopSaleRecord sale = new ChestShopSaleRecord(
                42L, "BUY", customer, 100, "BUSINESS", 7, null, false,
                "DIAMOND", "DIAMOND", "Diamond", false, null,
                3, new BigDecimal("5.00"), new BigDecimal("15.00"), new BigDecimal("0.30"),
                "world", 1, 2, 3, 64);

        api.recordSale(sale);

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(mapper).insertSale(cap.capture());
        Map<String, Object> p = cap.getValue();
        assertEquals(42L, p.get("txnId"));
        assertEquals("BUY", p.get("direction"));
        assertEquals(customer, p.get("customerUuid"));
        assertEquals(7, p.get("shopFirmId"));
        assertEquals("DIAMOND", p.get("itemKey"));
        assertEquals(3, p.get("quantity"));
        assertEquals(64, p.get("shopStock"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void upsertShop_buildsParamMapAndUpserts() {
        MarketApiImpl api = new MarketApiImpl(mapper);
        ChestShopShopRecord shop = new ChestShopShopRecord(
                "world", 1, 2, 3, false, 100, "PERSONAL", null, UUID.randomUUID(),
                "DIAMOND", "DIAMOND", "Diamond", false, null,
                new BigDecimal("5.00"), null, 1, 64, 192, UUID.randomUUID());

        api.upsertShop(shop);

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(mapper).upsertShop(cap.capture());
        Map<String, Object> p = cap.getValue();
        assertEquals("PERSONAL", p.get("shopAccountType"));
        assertEquals(64, p.get("currentStock"));
        assertEquals(192, p.get("estimatedCapacity"));
        assertEquals(1, p.get("batchQty"));
    }

    @Test
    void deactivateAndUpdateStock_delegate() {
        MarketApiImpl api = new MarketApiImpl(mapper);
        api.deactivateShop("world", 1, 2, 3);
        verify(mapper).deactivateShop("world", 1, 2, 3);
        api.updateShopStock("world", 1, 2, 3, 10, 54);
        verify(mapper).updateShopStock("world", 1, 2, 3, 10, 54);
    }

    @Test
    void visibilityHologramAndPreview_delegate() {
        MarketApiImpl api = new MarketApiImpl(mapper);
        UUID player = UUID.randomUUID();

        api.setShopVisibility("world", 1, 2, 3, false);
        verify(mapper).setShopVisibility("world", 1, 2, 3, false);

        api.setShopHologram("world", 4, 5, 6, true);
        verify(mapper).setShopHologram("world", 4, 5, 6, true);

        api.setPreviewPreference(player, false);
        verify(mapper).upsertPreviewPreference(player, false);
    }

    @Test
    void writes_failSoft_swallowMapperErrors() {
        MarketApiImpl api = new MarketApiImpl(mapper);
        UUID player = UUID.randomUUID();
        doThrow(new RuntimeException("db down")).when(mapper)
                .setShopVisibility(anyString(), anyInt(), anyInt(), anyInt(), anyBoolean());
        doThrow(new RuntimeException("db down")).when(mapper)
                .setShopHologram(anyString(), anyInt(), anyInt(), anyInt(), anyBoolean());
        doThrow(new RuntimeException("db down")).when(mapper)
                .upsertPreviewPreference(any(), anyBoolean());
        doThrow(new RuntimeException("db down")).when(mapper)
                .updateShopStock(anyString(), anyInt(), anyInt(), anyInt(), any(), any());
        doThrow(new RuntimeException("db down")).when(mapper)
                .deactivateShop(anyString(), anyInt(), anyInt(), anyInt());

        assertDoesNotThrow(() -> api.setShopVisibility("w", 0, 0, 0, true));
        assertDoesNotThrow(() -> api.setShopHologram("w", 0, 0, 0, true));
        assertDoesNotThrow(() -> api.setPreviewPreference(player, true));
        assertDoesNotThrow(() -> api.updateShopStock("w", 0, 0, 0, 1, 1));
        assertDoesNotThrow(() -> api.deactivateShop("w", 0, 0, 0));
    }

    @Test
    void failsSoft_swallowsMapperErrors() {
        MarketApiImpl api = new MarketApiImpl(mapper);
        doThrow(new RuntimeException("db down")).when(mapper).insertSale(anyMap());
        ChestShopSaleRecord sale = new ChestShopSaleRecord(
                null, "SELL", UUID.randomUUID(), null, null, null, null, true,
                "DIRT", "DIRT", "Dirt", false, null,
                1, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, "w", 0, 0, 0, null);
        assertDoesNotThrow(() -> api.recordSale(sale));
    }
}
