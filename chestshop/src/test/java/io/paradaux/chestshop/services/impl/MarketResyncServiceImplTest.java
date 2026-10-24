package io.paradaux.chestshop.services.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.services.MarketSyncService;
import io.paradaux.chestshop.services.PreviewService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.api.MarketApi;
import io.paradaux.treasury.api.ShopQueryApi;
import io.paradaux.treasury.api.market.ShopLocation;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code /find resync} world scanner. MockBukkit implements neither the scheduler nor chunk
 * tile-entity enumeration, so the plugin/server/scheduler and the chunks are mocked: the async
 * enumeration + main-thread hand-off run synchronously and the scan {@code BukkitRunnable} is
 * pumped by hand, exercising the real scan/upsert/deactivate logic.
 */
class MarketResyncServiceImplTest {

    private JavaPlugin plugin;
    private Server server;
    private BukkitScheduler scheduler;
    private MarketService marketService;
    private MarketApi marketApi;
    private ShopQueryApi shopQueryApi;
    private SignService signService;
    private MarketSyncService marketSync;
    private PreviewService previews;
    private Message message;
    private MarketResyncServiceImpl service;
    private Player initiator;

    private Runnable capturedTimer;
    private volatile boolean cancelled;
    private Field bukkitServer;
    private Server previousBukkitServer;

    @BeforeEach
    void wire() throws Exception {
        plugin = mock(JavaPlugin.class);
        server = mock(Server.class);
        scheduler = mock(BukkitScheduler.class);
        marketService = mock(MarketService.class);
        marketApi = mock(MarketApi.class);
        shopQueryApi = mock(ShopQueryApi.class);
        signService = mock(SignService.class);
        marketSync = mock(MarketSyncService.class);
        previews = mock(PreviewService.class);
        message = mock(Message.class);
        initiator = mock(Player.class);

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        lenient().when(marketService.market()).thenReturn(marketApi);
        lenient().when(marketService.shopQuery()).thenReturn(shopQueryApi);
        lenient().when(marketService.enabled()).thenReturn(true);
        lenient().when(marketService.searchEnabled()).thenReturn(true);

        // Run the async enumeration and the main-thread hand-off synchronously.
        lenient()
                .when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class)))
                .thenAnswer(inv -> {
                    ((Runnable) inv.getArgument(1)).run();
                    return mock(BukkitTask.class);
                });
        lenient().when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return mock(BukkitTask.class);
        });
        lenient()
                .when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    capturedTimer = inv.getArgument(1);
                    BukkitTask task = mock(BukkitTask.class);
                    when(task.getTaskId()).thenReturn(7);
                    return task;
                });
        doAnswer(inv -> {
                    cancelled = true;
                    return null;
                })
                .when(scheduler)
                .cancelTask(anyInt());

        // BukkitRunnable.runTaskTimer/cancel resolve the scheduler via the static Bukkit server.
        bukkitServer = Bukkit.class.getDeclaredField("server");
        bukkitServer.setAccessible(true);
        previousBukkitServer = (Server) bukkitServer.get(null);
        bukkitServer.set(null, server);

        service = new MarketResyncServiceImpl(plugin, marketService, signService, marketSync, previews, message);
    }

    @AfterEach
    void restore() throws Exception {
        bukkitServer.set(null, previousBukkitServer);
    }

    private void driveScan() {
        int guard = 0;
        while (!cancelled && capturedTimer != null && guard++ < 5000) {
            capturedTimer.run();
        }
    }

    // ── entry guards ──────────────────────────────────────────────────────────

    @Test
    void resync_marketDisabled_reportsNoSearch() {
        when(marketService.enabled()).thenReturn(false);
        service.resync(initiator, 4);
        verify(message).send(initiator, "find.no-search");
        verify(scheduler, never()).runTaskAsynchronously(any(), any(Runnable.class));
    }

    @Test
    void resync_searchDisabled_reportsNoSearch() {
        when(marketService.enabled()).thenReturn(true);
        when(marketService.searchEnabled()).thenReturn(false);
        service.resync(initiator, 4);
        verify(message).send(initiator, "find.no-search");
    }

    @Test
    void resync_alreadyRunning_reportsBusy() throws Exception {
        Field running = MarketResyncServiceImpl.class.getDeclaredField("running");
        running.setAccessible(true);
        ((AtomicBoolean) running.get(service)).set(true);

        service.resync(initiator, 4);
        verify(message).send(initiator, "chestshop.resync.busy");
        verify(scheduler, never()).runTaskAsynchronously(any(), any(Runnable.class));
    }

    @Test
    void resync_enumerationFailure_reportsFailed_andReleasesTheLock() throws Exception {
        when(shopQueryApi.activeShopLocations(null)).thenThrow(new RuntimeException("db down"));
        service.resync(initiator, 4);
        verify(message).send(initiator, "chestshop.resync.queued");
        verify(message).send(initiator, "chestshop.resync.failed");

        // The running lock was released, so a subsequent resync can start again.
        Field running = MarketResyncServiceImpl.class.getDeclaredField("running");
        running.setAccessible(true);
        org.assertj.core.api.Assertions.assertThat(((AtomicBoolean) running.get(service)).get())
                .isFalse();
    }

    // ── full scan ─────────────────────────────────────────────────────────────

    private Sign sign(int x, int y, int z, String owner, String item) {
        Sign s = mock(Sign.class);
        lenient().when(s.getLines()).thenReturn(new String[] {owner, "1", "B 10", item});
        lenient().when(s.getX()).thenReturn(x);
        lenient().when(s.getY()).thenReturn(y);
        lenient().when(s.getZ()).thenReturn(z);
        return s;
    }

    private Chunk chunk(World world, int cx, int cz, boolean loaded, BlockState... tiles) {
        Chunk c = mock(Chunk.class);
        lenient().when(c.getWorld()).thenReturn(world);
        lenient().when(c.getX()).thenReturn(cx);
        lenient().when(c.getZ()).thenReturn(cz);
        lenient().when(c.isLoaded()).thenReturn(loaded);
        lenient().when(c.getTileEntities(false)).thenReturn(tiles);
        return c;
    }

    @Test
    void resync_scansChunk_upsertsValidShops_deactivatesVanished_andCompletes() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(server.getWorlds()).thenReturn(List.of(world));

        Sign registered = sign(5, 64, 5, "Owner", "Diamond");
        Sign alsoRegistered = sign(6, 64, 6, "admin", "Stone");
        Sign unresolvable = sign(7, 64, 7, "Owner2", "ruby_sword"); // present, but its row can't be built
        Sign invalid = sign(8, 64, 8, "X", "Diamond");
        BlockState nonSign = mock(BlockState.class);

        for (Sign s : List.of(registered, alsoRegistered, unresolvable)) {
            when(signService.isValid(s)).thenReturn(true);
        }
        when(signService.isValid(invalid)).thenReturn(false);
        when(marketSync.registerShop(registered)).thenReturn(true);
        when(marketSync.registerShop(alsoRegistered)).thenReturn(true);
        when(marketSync.registerShop(unresolvable)).thenReturn(false);

        when(shopQueryApi.activeShopLocations(null))
                .thenReturn(List.of(
                        new ShopLocation("world", UUID.randomUUID(), 5, 64, 5), // registered -> kept
                        new ShopLocation("world", UUID.randomUUID(), 7, 64, 7), // sign present, upsert failed -> kept
                        new ShopLocation(
                                "world", UUID.randomUUID(), 8, 64, 8), // no longer a valid shop sign -> deactivate
                        new ShopLocation("world", UUID.randomUUID(), 9, 64, 9))); // no sign at all -> deactivate

        Chunk c1 = chunk(world, 0, 0, true, registered, alsoRegistered, unresolvable, invalid, nonSign);
        Chunk c2 = chunk(world, 1, 0, false); // enqueued but unloaded before processing -> skipped
        when(world.getLoadedChunks()).thenReturn(new Chunk[] {c1, c2});

        service.resync(initiator, 3);
        driveScan();

        verify(marketSync, never()).registerShop(invalid);
        // A shop whose sign is physically present is never deactivated just because it couldn't be upserted.
        verify(marketApi, never()).deactivateShop("world", 7, 64, 7);
        verify(previews, never()).destroy("world", 7, 64, 7);
        verify(marketApi).deactivateShop("world", 8, 64, 8);
        verify(marketApi).deactivateShop("world", 9, 64, 9);
        verify(previews).destroy("world", 8, 64, 8); // the vanished shops' holograms go with them
        verify(previews).destroy("world", 9, 64, 9);
        verify(previews).loadChunk("world", 0, 0); // previews for the shops just (re)discovered
        verify(message).send(initiator, "chestshop.resync.started", "chunks", 2);
        verify(message).send(initiator, "chestshop.resync.complete", "upserted", 2, "deactivated", 2);
    }

    @Test
    void resync_chunkWithNothingToRegister_doesNotReloadItsPreviews() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(server.getWorlds()).thenReturn(List.of(world));
        when(shopQueryApi.activeShopLocations(null)).thenReturn(List.of());
        Chunk empty = chunk(world, 0, 0, true);
        when(world.getLoadedChunks()).thenReturn(new Chunk[] {empty});

        service.resync(initiator, 1);
        driveScan();

        verify(previews, never()).loadChunk(any(), anyInt(), anyInt());
    }

    @Test
    void resync_clampsChunksPerTick_soOneTickCannotScanTheWholeWorld() {
        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn("world");
        when(server.getWorlds()).thenReturn(List.of(world));
        when(shopQueryApi.activeShopLocations(null)).thenReturn(List.of());
        Chunk[] chunks = new Chunk[MarketResyncServiceImpl.MAX_CHUNKS_PER_TICK + 1];
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = chunk(world, i, 0, true);
        }
        when(world.getLoadedChunks()).thenReturn(chunks);

        service.resync(initiator, Integer.MAX_VALUE);

        capturedTimer.run(); // first tick: only the clamped batch
        verify(chunks[MarketResyncServiceImpl.MAX_CHUNKS_PER_TICK - 1]).getTileEntities(false);
        verify(chunks[MarketResyncServiceImpl.MAX_CHUNKS_PER_TICK], never()).getTileEntities(false);
        verify(message, never()).send(eq(initiator), eq("chestshop.resync.complete"), any(), any(), any(), any());

        capturedTimer.run(); // second tick finishes the remainder
        verify(message).send(initiator, "chestshop.resync.complete", "upserted", 0, "deactivated", 0);
    }

    @Test
    void resync_emitsProgress_forLongScans() {
        World world = mock(World.class);
        lenient().when(world.getName()).thenReturn("world");
        when(server.getWorlds()).thenReturn(List.of(world));
        when(shopQueryApi.activeShopLocations(null)).thenReturn(List.of()); // nothing known

        // 201 empty chunks with perTick=1 -> a progress tick fires at processed == 200.
        Chunk[] chunks = new Chunk[201];
        for (int i = 0; i < chunks.length; i++) {
            chunks[i] = chunk(world, 0, 0, true);
        }
        when(world.getLoadedChunks()).thenReturn(chunks);

        service.resync(initiator, 0); // chunksPerTick 0 -> perTick max(1,0) == 1
        driveScan();

        verify(message).send(initiator, "chestshop.resync.progress", "done", 200, "total", 201);
        verify(message).send(initiator, "chestshop.resync.complete", "upserted", 0, "deactivated", 0);
    }

    @Test
    void resync_noLoadedChunks_completesImmediately() {
        World world = mock(World.class);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getLoadedChunks()).thenReturn(new Chunk[0]);
        when(shopQueryApi.activeShopLocations(null)).thenReturn(List.of());

        service.resync(initiator, 2);
        driveScan();

        verify(message).send(initiator, "chestshop.resync.started", "chunks", 0);
        verify(message).send(initiator, "chestshop.resync.complete", "upserted", 0, "deactivated", 0);
        verify(message, never()).send(eq(initiator), eq("chestshop.resync.progress"), any(), any(), any(), any());
    }

    @Test
    void discovery_deduplicatesChunksAndWaitsBetweenScans() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(server.getWorld("world")).thenReturn(world);
        Sign live = sign(1, 64, 1, "Owner", "STONE");
        when(signService.isValid(live)).thenReturn(true);
        when(marketSync.registerShop(live)).thenReturn(true);
        Chunk first = chunk(world, 0, 0, true, live);
        Chunk second = chunk(world, 1, 0, true);
        var vanished = mock(io.paradaux.treasury.api.market.ShopResult.class);
        when(vanished.world()).thenReturn("world");
        when(vanished.signX()).thenReturn(2);
        when(vanished.signY()).thenReturn(64);
        when(vanished.signZ()).thenReturn(2);
        when(shopQueryApi.shopsInChunk("world", 0, 0)).thenReturn(List.of(vanished));
        java.util.Deque<Runnable> async = new java.util.ArrayDeque<>();
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            async.add(inv.getArgument(1));
            return mock(BukkitTask.class);
        });
        service.discoverChunk(first);
        service.discoverChunk(first);
        service.discoverChunk(second);
        org.assertj.core.api.Assertions.assertThat(async).isEmpty();
        capturedTimer.run(); // start the first background read
        org.assertj.core.api.Assertions.assertThat(async).hasSize(1);
        async.remove().run();
        verify(marketSync, never()).registerShop(any()); // no async world access
        capturedTimer.run(); // apply its result, but do not start the next read this tick
        verify(marketSync).registerShop(live);
        verify(marketApi).deactivateShop("world", 2, 64, 2);
        verify(previews).destroy("world", 2, 64, 2);
        org.assertj.core.api.Assertions.assertThat(async).isEmpty();
        verify(scheduler, never()).runTaskLater(eq(plugin), any(Runnable.class), anyLong());
        verify(scheduler, never()).runTask(eq(plugin), any(Runnable.class));
        capturedTimer.run();
        org.assertj.core.api.Assertions.assertThat(async).hasSize(1);
        async.remove().run();
        capturedTimer.run();
        verify(shopQueryApi).shopsInChunk("world", 0, 0);
        verify(shopQueryApi).shopsInChunk("world", 1, 0);
        org.assertj.core.api.Assertions.assertThat(async).isEmpty();
        org.assertj.core.api.Assertions.assertThat(cancelled).isTrue();
    }

    @Test
    void discovery_doesNotInspectUnloadedChunksOrReplacedWorlds() {
        for (boolean replacement : List.of(false, true)) {
            World original = mock(World.class);
            when(original.getName()).thenReturn("world");
            when(original.getUID()).thenReturn(UUID.randomUUID());
            World current = replacement ? mock(World.class) : original;
            if (replacement) when(current.getUID()).thenReturn(UUID.randomUUID());
            when(server.getWorld("world")).thenReturn(current);
            Chunk chunk = chunk(original, 0, 0, replacement);
            service = new MarketResyncServiceImpl(plugin, marketService, signService, marketSync, previews, message);
            cancelled = false;
            service.discoverChunk(chunk);
            driveScan();
            verify(chunk, never()).getTileEntities(false);
        }
        verify(marketSync, never()).registerShop(any());
        verify(marketApi, never()).deactivateShop(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void discovery_databaseFailureDoesNotTreatEveryShopAsMissing() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        Chunk chunk = chunk(world, 0, 0, true);
        when(server.getWorld("world")).thenReturn(world);
        when(shopQueryApi.shopsInChunk("world", 0, 0)).thenThrow(new IllegalStateException("offline"));
        service.discoverChunk(chunk);
        driveScan();
        verify(chunk, never()).getTileEntities(false);
        verify(marketApi, never()).deactivateShop(any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void discovery_atEnableQueuesAlreadyLoadedChunks() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(server.getWorld("world")).thenReturn(world);
        Chunk chunk = chunk(world, 0, 0, true);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getLoadedChunks()).thenReturn(new Chunk[] {chunk});
        service.discoverLoadedChunks();
        driveScan();
        verify(shopQueryApi).shopsInChunk("world", 0, 0);
        verify(chunk).getTileEntities(false);
    }
}
