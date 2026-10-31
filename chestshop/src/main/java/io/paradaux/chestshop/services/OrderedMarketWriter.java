package io.paradaux.chestshop.services;

import io.paradaux.treasury.api.MarketApi;
import io.paradaux.treasury.api.market.ChestShopSaleRecord;
import io.paradaux.treasury.api.market.ChestShopShopRecord;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * One FIFO for registry mutations from discovery, trades, toggles and hopper observations.
 * Only immutable records/scalars cross this boundary; no Bukkit objects enter the worker.
 * Sale recording stays synchronous because Treasury dispatches a Bukkit sale event there.
 */
@Slf4j
public final class OrderedMarketWriter implements MarketApi, AutoCloseable {
    private final MarketApi delegate;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("ChestShop-registry").factory());

    public OrderedMarketWriter(MarketApi delegate) {
        this.delegate = delegate;
    }

    private void enqueue(Runnable write) {
        worker.execute(() -> {
            try {
                write.run();
            } catch (RuntimeException e) {
                log.warn("ChestShop registry write failed", e);
            }
        });
    }

    /** Off-thread read barrier: wait only for mutations submitted before this call. */
    public void awaitWrites() {
        CompletableFuture.runAsync(() -> {}, worker).join();
    }

    @Override
    public void recordSale(ChestShopSaleRecord sale) {
        delegate.recordSale(sale);
    }

    @Override
    public void upsertShop(ChestShopShopRecord shop) {
        enqueue(() -> delegate.upsertShop(shop));
    }

    @Override
    public void deactivateShop(String world, int x, int y, int z) {
        enqueue(() -> delegate.deactivateShop(world, x, y, z));
    }

    @Override
    public void updateShopStock(String world, int x, int y, int z, Integer stock, Integer capacity) {
        enqueue(() -> delegate.updateShopStock(world, x, y, z, stock, capacity));
    }

    @Override
    public void setShopVisibility(String world, int x, int y, int z, boolean visible) {
        enqueue(() -> delegate.setShopVisibility(world, x, y, z, visible));
    }

    @Override
    public void setShopHologram(String world, int x, int y, int z, boolean visible) {
        enqueue(() -> delegate.setShopHologram(world, x, y, z, visible));
    }

    @Override
    public void setPreviewPreference(UUID player, boolean visible) {
        enqueue(() -> delegate.setPreviewPreference(player, visible));
    }

    // Import callers already run off-thread and require persistence failures to propagate.
    @Override
    public void importShop(ChestShopShopRecord shop, boolean visible, boolean hologram, Instant observedAt) {
        CompletableFuture.runAsync(() -> delegate.importShop(shop, visible, hologram, observedAt), worker)
                .join();
    }

    @Override
    public void importPreviewPreference(UUID player, boolean visible) {
        CompletableFuture.runAsync(() -> delegate.importPreviewPreference(player, visible), worker)
                .join();
    }

    @Override
    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS)) {
                log.warn("ChestShop registry writer did not drain within 15 seconds during shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while draining ChestShop registry writes");
        }
    }
}
