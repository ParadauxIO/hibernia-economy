package io.paradaux.chestshop.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.paradaux.treasury.api.MarketApi;
import java.util.ArrayDeque;
import java.util.Deque;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Sign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Registry stock writes for hopper refreshes (PAR-544): unchanged readings write nothing, the
 * observations reach the ordered writer on the next tick, and newer authoritative writes
 * supersede pending values. The main-thread scheduler is driven explicitly.
 */
class ShopStockPublisherTest {

    private MarketService market;
    private MarketApi api;
    private ItemService items;
    private World world;
    private Inventory inventory;
    private final Deque<Runnable> pending = new ArrayDeque<>();
    private ShopStockPublisher publisher;
    private final ItemStack diamond = mock(ItemStack.class);

    @BeforeEach
    void setUp() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        lenient()
                .when(scheduler.runTask(any(Plugin.class), any(Runnable.class)))
                .thenAnswer(call -> {
                    pending.add(call.getArgument(1));
                    return null;
                });
        market = mock(MarketService.class);
        api = mock(MarketApi.class);
        items = mock(ItemService.class);
        when(market.enabled()).thenReturn(true);
        lenient().when(market.market()).thenReturn(api);
        lenient().when(items.parse("Diamond")).thenReturn(diamond);
        world = mock(World.class);
        lenient().when(world.getName()).thenReturn("world");
        inventory = mock(Inventory.class);
        publisher = new ShopStockPublisher(plugin, market, items);
    }

    private Sign sign(int x, String item) {
        Sign sign = mock(Sign.class);
        lenient().when(sign.getLines()).thenReturn(new String[] {"Notch", "1", "B 5", item});
        lenient().when(sign.getLocation()).thenReturn(new Location(world, x, 64, 0));
        return sign;
    }

    private void measure(int stock, int capacity) {
        when(market.stockOf(diamond, inventory)).thenReturn(stock);
        when(market.capacityOf(diamond, inventory)).thenReturn(capacity);
    }

    /** Submit one coalesced batch to the ordered registry writer. */
    private void completeBatch() {
        pending.removeFirst().run();
    }

    @Test
    void submitsChangedStockOnTheNextTick() {
        measure(12, 100);
        publisher.publish(sign(1, "Diamond"), inventory);

        verifyNoInteractions(api); // nothing on the calling (main) thread
        completeBatch();
        verify(api).updateShopStock("world", 1, 64, 0, 12, 100);
    }

    @Test
    void unchangedReadingWritesNothing() {
        measure(12, 100);
        Sign sign = sign(1, "Diamond");
        publisher.publish(sign, inventory);
        completeBatch();
        publisher.publish(sign, inventory);

        assertThat(pending).isEmpty();
        verify(api, org.mockito.Mockito.times(1))
                .updateShopStock(anyString(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void readingsBeforeTheNextTickCoalesceToTheNewest() {
        Sign sign = sign(1, "Diamond");
        measure(12, 100);
        publisher.publish(sign, inventory);
        measure(11, 101);
        publisher.publish(sign, inventory);
        measure(10, 102);
        publisher.publish(sign, inventory);

        assertThat(pending).hasSize(1); // one next-tick batch
        completeBatch();
        // Both older readings were superseded before submission.
        verify(api).updateShopStock("world", 1, 64, 0, 10, 102);
        verify(api, org.mockito.Mockito.times(1))
                .updateShopStock(anyString(), anyInt(), anyInt(), anyInt(), any(), any());
        assertThat(pending).isEmpty();
    }

    @Test
    void batchesForDifferentReadingsLandInOrder() {
        Sign sign = sign(1, "Diamond");
        measure(12, 100);
        publisher.publish(sign, inventory);
        completeBatch();
        measure(10, 102);
        publisher.publish(sign, inventory);
        completeBatch();

        var order = inOrder(api);
        order.verify(api).updateShopStock("world", 1, 64, 0, 12, 100);
        order.verify(api).updateShopStock("world", 1, 64, 0, 10, 102);
    }

    @Test
    void tradeWriteSupersedesAPendingHopperReading() {
        Sign sign = sign(1, "Diamond");
        measure(12, 100);
        publisher.publish(sign, inventory);
        // A trade submitted 11 to the writer before the next tick.
        publisher.recorded(sign.getLocation(), 11, 101);
        completeBatch();

        verify(api, never()).updateShopStock(anyString(), anyInt(), anyInt(), anyInt(), any(), any());
        // The trade's value is the new baseline, so re-reading it writes nothing.
        measure(11, 101);
        publisher.publish(sign, inventory);
        assertThat(pending).isEmpty();
    }

    @Test
    void recordedValueChangesTheBaselineForLaterReadings() {
        Sign sign = sign(1, "Diamond");
        publisher.recorded(sign.getLocation(), 12, 100);
        measure(12, 100);
        publisher.publish(sign, inventory);
        assertThat(pending).isEmpty();

        measure(9, 103);
        publisher.publish(sign, inventory);
        completeBatch();
        verify(api).updateShopStock("world", 1, 64, 0, 9, 103);
    }

    @Test
    void forgottenShopIsNotWritten() {
        Sign sign = sign(1, "Diamond");
        measure(12, 100);
        publisher.publish(sign, inventory);
        publisher.forget(sign.getLocation());
        completeBatch();

        verify(api, never()).updateShopStock(anyString(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void wildcardUnknownItemAndDisabledRegistryAreSkipped() {
        measure(12, 100);
        // Even if a wildcard code parsed, wildcard shops have no registry row to update.
        lenient().when(items.parse("diamond_*")).thenReturn(diamond);
        publisher.publish(sign(1, "?"), inventory);
        publisher.publish(sign(2, "diamond_*"), inventory);
        publisher.publish(sign(3, "Unobtainium"), inventory);
        when(market.enabled()).thenReturn(false);
        publisher.publish(sign(4, "Diamond"), inventory);

        assertThat(pending).isEmpty();
        verifyNoInteractions(api);
    }

    @Test
    void chunkUnloadDropsOnlyTheBaseline() {
        Sign sign = sign(1, "Diamond");
        measure(12, 100);
        publisher.publish(sign, inventory);
        completeBatch();
        publisher.unloadChunk("world", 0, 0);
        // Without a baseline the same reading is written again on the next refresh.
        publisher.publish(sign, inventory);
        completeBatch();

        verify(api, org.mockito.Mockito.times(2)).updateShopStock("world", 1, 64, 0, 12, 100);
    }

    @Test
    void flushSubmitsAllPendingStockBeforeShutdown() {
        Sign first = sign(1, "Diamond");
        Sign second = sign(2, "Diamond");
        measure(12, 100);
        publisher.publish(first, inventory); // pending for next tick
        publisher.publish(second, inventory); // coalesced into the same tick

        publisher.flush();
        verify(api).updateShopStock("world", 2, 64, 0, 12, 100);
        // The scheduled callback after flush is harmless.
        completeBatch();
        verify(api).updateShopStock("world", 1, 64, 0, 12, 100);
    }

    @Test
    void flushSubmitsOnlyTheNewestReading() {
        Sign sign = sign(1, "Diamond");
        measure(12, 100);
        publisher.publish(sign, inventory); // pending for next tick
        measure(9, 103);
        publisher.publish(sign, inventory); // queued

        publisher.flush();
        completeBatch();
        verify(api).updateShopStock("world", 1, 64, 0, 9, 103);
        verify(api, never()).updateShopStock("world", 1, 64, 0, 12, 100);
    }

    @Test
    void writeFailuresAreContained() {
        measure(12, 100);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(api)
                .updateShopStock(anyString(), anyInt(), anyInt(), anyInt(), any(), any());
        publisher.publish(sign(1, "Diamond"), inventory);

        completeBatch();
        // The next observation can schedule a new submission.
        measure(5, 107);
        publisher.publish(sign(1, "Diamond"), inventory);
        assertThat(pending).hasSize(1);
    }

    @Test
    void signsInAnUnloadedWorldHaveNoRegistryIdentity() {
        Location orphan = new Location(null, 1, 64, 0);
        Sign sign = sign(1, "Diamond");
        when(sign.getLocation()).thenReturn(orphan);
        measure(12, 100);

        publisher.publish(sign, inventory);
        publisher.recorded(orphan, 12, 100);
        publisher.forget(orphan);
        assertThat(pending).isEmpty();
    }

    @Test
    void unloadKeepsBaselinesOutsideThatChunkAndWorld() {
        World nether = mock(World.class);
        when(nether.getName()).thenReturn("nether");
        Sign here = sign(1, "Diamond");
        Sign otherChunkX = sign(40, "Diamond");
        Sign otherChunkZ = sign(2, "Diamond");
        when(otherChunkZ.getLocation()).thenReturn(new Location(world, 2, 64, 40));
        Sign otherWorld = sign(3, "Diamond");
        when(otherWorld.getLocation()).thenReturn(new Location(nether, 3, 64, 0));
        measure(12, 100);
        for (Sign sign : java.util.List.of(here, otherChunkX, otherChunkZ, otherWorld)) {
            publisher.publish(sign, inventory);
            completeBatch();
        }

        publisher.unloadChunk("world", 0, 0);
        publisher.unloadWorld("elsewhere");
        for (Sign sign : java.util.List.of(otherChunkX, otherChunkZ, otherWorld)) publisher.publish(sign, inventory);
        assertThat(pending).isEmpty(); // their baselines survived
        publisher.unloadWorld("nether");
        publisher.publish(otherWorld, inventory);
        assertThat(pending).hasSize(1);
    }

    @Test
    void flushWithoutAMarketApiWritesNothing() {
        measure(12, 100);
        publisher.publish(sign(1, "Diamond"), inventory);
        when(market.market()).thenReturn(null);

        completeBatch();
        publisher.flush();
        verifyNoInteractions(api);
    }

    @Test
    void forgetByCoordinatesMatchesTheSignLocation() {
        measure(12, 100);
        publisher.publish(sign(1, "Diamond"), inventory);
        publisher.forget("world", 1, 64, 0);
        completeBatch();

        verifyNoInteractions(api);
    }

    @Test
    void forgettingOneShopPreservesOtherBaselinesInTheSameChunk() {
        measure(12, 100);
        Sign first = sign(1, "Diamond");
        Sign second = sign(2, "Diamond");
        publisher.recorded(first.getLocation(), 12, 100);
        publisher.recorded(second.getLocation(), 12, 100);
        publisher.forget(first.getLocation());
        publisher.forget("absent", 1, 64, 0);
        publisher.publish(second, inventory);
        assertThat(pending).isEmpty();
        publisher.publish(first, inventory);
        completeBatch();
        verify(api).updateShopStock("world", 1, 64, 0, 12, 100);
        verify(api, never()).updateShopStock("world", 2, 64, 0, 12, 100);
    }

    @Test
    void failedSubmissionAfterChunkUnloadDoesNotPreventRetryOnReload() {
        measure(12, 100);
        Sign sign = sign(-1, "Diamond");
        publisher.publish(sign, inventory);
        publisher.unloadChunk("world", -1, 0);
        org.mockito.Mockito.doThrow(new IllegalStateException("writer unavailable"))
                .doNothing()
                .when(api)
                .updateShopStock("world", -1, 64, 0, 12, 100);
        completeBatch();
        publisher.publish(sign, inventory);
        completeBatch();
        verify(api, org.mockito.Mockito.times(2)).updateShopStock("world", -1, 64, 0, 12, 100);
    }
}
