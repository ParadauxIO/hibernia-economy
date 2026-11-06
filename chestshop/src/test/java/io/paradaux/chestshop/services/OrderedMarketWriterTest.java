package io.paradaux.chestshop.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.paradaux.treasury.api.MarketApi;
import io.paradaux.treasury.api.market.ChestShopSaleRecord;
import io.paradaux.treasury.api.market.ChestShopShopRecord;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class OrderedMarketWriterTest {
    @Test
    void blockedDatabaseDoesNotBlockCallerAndLaterMutationsKeepTheirOrder() throws Exception {
        MarketApi database = mock(MarketApi.class);
        var shop = mock(ChestShopShopRecord.class);
        Thread caller = Thread.currentThread();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
                    assertThat(Thread.currentThread()).isNotSameAs(caller);
                    entered.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    return null;
                })
                .when(database)
                .upsertShop(shop);
        try (var writer = new OrderedMarketWriter(database)) {
            try {
                writer.upsertShop(shop);
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                writer.updateShopStock("world", 1, 64, -1, 12, 100);
                writer.deactivateShop("world", 1, 64, -1);
                writer.setShopVisibility("world", 1, 64, -1, false);
                writer.setShopHologram("world", 1, 64, -1, false);
                verify(database, never()).deactivateShop(anyString(), anyInt(), anyInt(), anyInt());
            } finally {
                release.countDown();
            }
            writer.awaitWrites();
            var order = inOrder(database);
            order.verify(database).upsertShop(shop);
            order.verify(database).updateShopStock("world", 1, 64, -1, 12, 100);
            order.verify(database).deactivateShop("world", 1, 64, -1);
            order.verify(database).setShopVisibility("world", 1, 64, -1, false);
            order.verify(database).setShopHologram("world", 1, 64, -1, false);
        }
    }

    @Test
    void saleEventRemainsOnTheCallingThread() {
        MarketApi database = mock(MarketApi.class);
        var sale = mock(ChestShopSaleRecord.class);
        Thread caller = Thread.currentThread();
        doAnswer(call -> {
                    assertThat(Thread.currentThread()).isSameAs(caller);
                    return null;
                })
                .when(database)
                .recordSale(sale);
        try (var writer = new OrderedMarketWriter(database)) {
            writer.recordSale(sale);
            verify(database).recordSale(sale);
        }
    }

    @Test
    void failedWriteDoesNotPoisonLaterWritesAndCloseDrainsThem() {
        MarketApi database = mock(MarketApi.class);
        var shop = mock(ChestShopShopRecord.class);
        UUID player = UUID.randomUUID();
        doThrow(new IllegalStateException("database unavailable"))
                .when(database)
                .upsertShop(shop);
        try (var writer = new OrderedMarketWriter(database)) {
            writer.upsertShop(shop);
            writer.setPreviewPreference(player, false);
        }
        verify(database).setPreviewPreference(player, false);
    }

    @Test
    void importsAreOrderedAndReportPersistenceFailures() {
        MarketApi database = mock(MarketApi.class);
        var shop = mock(ChestShopShopRecord.class);
        UUID player = UUID.randomUUID();
        try (var writer = new OrderedMarketWriter(database)) {
            writer.upsertShop(shop);
            writer.importShop(shop, false, true, null);
            writer.importPreviewPreference(player, false);
            var order = inOrder(database);
            order.verify(database).upsertShop(shop);
            order.verify(database).importShop(shop, false, true, null);
            order.verify(database).importPreviewPreference(player, false);
            doThrow(new IllegalStateException("database unavailable"))
                    .when(database)
                    .importPreviewPreference(player, true);
            assertThatThrownBy(() -> writer.importPreviewPreference(player, true))
                    .hasRootCauseInstanceOf(IllegalStateException.class);
        }
    }
}
