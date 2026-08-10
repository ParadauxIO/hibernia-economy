package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.ItemCodeService;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.api.MarketApi;
import io.paradaux.treasury.api.ShopQueryApi;
import io.paradaux.treasury.api.market.ChestShopShopRecord;
import io.paradaux.treasury.api.market.ShopLocation;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private ShopBlockService shopBlockService;
    private ItemCodeService itemCodes;
    private AccountService accounts;
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
        shopBlockService = mock(ShopBlockService.class);
        itemCodes = mock(ItemCodeService.class);
        accounts = mock(AccountService.class);
        message = mock(Message.class);
        initiator = mock(Player.class);

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        lenient().when(marketService.market()).thenReturn(marketApi);
        lenient().when(marketService.shopQuery()).thenReturn(shopQueryApi);
        lenient().when(marketService.enabled()).thenReturn(true);
        lenient().when(marketService.searchEnabled()).thenReturn(true);

        // Run the async enumeration and the main-thread hand-off synchronously.
        lenient().when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return mock(BukkitTask.class);
        });
        lenient().when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return mock(BukkitTask.class);
        });
        lenient().when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                .thenAnswer(inv -> {
                    capturedTimer = inv.getArgument(1);
                    BukkitTask task = mock(BukkitTask.class);
                    when(task.getTaskId()).thenReturn(7);
                    return task;
                });
        doAnswer(inv -> {
            cancelled = true;
            return null;
        }).when(scheduler).cancelTask(anyInt());

        // BukkitRunnable.runTaskTimer/cancel resolve the scheduler via the static Bukkit server.
        bukkitServer = Bukkit.class.getDeclaredField("server");
        bukkitServer.setAccessible(true);
        previousBukkitServer = (Server) bukkitServer.get(null);
        bukkitServer.set(null, server);

        service = new MarketResyncServiceImpl(plugin, marketService, signService, shopBlockService,
                itemCodes, accounts, message);
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
        org.assertj.core.api.Assertions.assertThat(((AtomicBoolean) running.get(service)).get()).isFalse();
    }

    // ── full scan ─────────────────────────────────────────────────────────────

    private Sign sign(int x, int y, int z, String owner, String item) {
        Sign s = mock(Sign.class);
        lenient().when(s.getLines()).thenReturn(new String[]{owner, "1", "B 10", item});
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

        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        ItemStack diamond = mock(ItemStack.class);
        ItemStack stone = mock(ItemStack.class);
        ItemStack gold = mock(ItemStack.class);
        ItemStack iron = mock(ItemStack.class);
        ItemStack coal = mock(ItemStack.class);
        when(itemCodes.decode("Diamond")).thenReturn(diamond);
        when(itemCodes.decode("Stone")).thenReturn(stone);
        when(itemCodes.decode("Gold")).thenReturn(gold);
        when(itemCodes.decode("Bad")).thenReturn(null);
        when(itemCodes.decode("Iron")).thenReturn(iron);
        when(itemCodes.decode("Coal")).thenReturn(coal);
        when(itemCodes.decode("Boom")).thenThrow(new RuntimeException("bad blob"));

        // Signs.
        Sign personalOk = sign(5, 64, 5, "Owner", "Diamond");
        Sign adminOk = sign(6, 64, 6, "admin", "Stone");
        Sign personalNoChest = sign(7, 64, 7, "Owner2", "Gold");
        Sign itemNull = mock(Sign.class);
        lenient().when(itemNull.getLines()).thenReturn(new String[]{"O", "1", "B 1", null}); // item line null
        Sign decodeNull = sign(0, 0, 0, "O", "Bad");
        Sign accountNull = sign(0, 0, 0, "NoAcc", "Iron");
        Sign uuidNull = sign(0, 0, 0, "NoUuid", "Coal");
        Sign throwing = sign(0, 0, 0, "T", "Boom");
        Sign invalid = sign(0, 0, 0, "X", "Diamond");
        BlockState nonSign = mock(BlockState.class);

        // Validity / admin classification.
        for (Sign s : List.of(personalOk, adminOk, personalNoChest, itemNull, decodeNull, accountNull, uuidNull, throwing)) {
            when(signService.isValid(s)).thenReturn(true);
            lenient().when(signService.isAdminShop(s)).thenReturn(false);
        }
        when(signService.isValid(invalid)).thenReturn(false);
        when(signService.isAdminShop(adminOk)).thenReturn(true);

        // Owner resolution.
        when(accounts.resolveAccount("Owner")).thenReturn(new Account("Owner", "o", u1));
        when(accounts.resolveAccount("Owner2")).thenReturn(new Account("Owner2", "o2", u2));
        when(accounts.resolveAccount("NoAcc")).thenReturn(null);
        when(accounts.resolveAccount("NoUuid")).thenReturn(new Account("NoUuid", "n", null));
        MarketService.Owner ownerP = new MarketService.Owner(1, "PERSONAL", null, u1, false);
        MarketService.Owner ownerP2 = new MarketService.Owner(2, "PERSONAL", null, u2, false);
        MarketService.Owner ownerAdmin = new MarketService.Owner(null, null, null, null, true);
        when(marketService.ownerFromUuid(u1, false)).thenReturn(ownerP);
        when(marketService.ownerFromUuid(u2, false)).thenReturn(ownerP2);
        when(marketService.ownerFromUuid(null, true)).thenReturn(ownerAdmin);

        // Containers / stock.
        Container container = mock(Container.class);
        Inventory inv = mock(Inventory.class);
        when(container.getInventory()).thenReturn(inv);
        when(shopBlockService.findConnectedContainer(personalOk)).thenReturn(container);
        when(shopBlockService.findConnectedContainer(personalNoChest)).thenReturn(null);
        lenient().when(marketService.stockOf(any(), eq(inv))).thenReturn(12);
        lenient().when(marketService.capacityOf(any(), eq(inv))).thenReturn(52);
        lenient().when(marketService.shop(any(), any(), any(), any(), any())).thenReturn(mock(ChestShopShopRecord.class));

        // Known shops: one still-present (skipped), one vanished (deactivated).
        when(shopQueryApi.activeShopLocations(null)).thenReturn(List.of(
                new ShopLocation("world", UUID.randomUUID(), 5, 64, 5),   // matches personalOk -> kept
                new ShopLocation("world", UUID.randomUUID(), 9, 64, 9))); // no live sign -> deactivate

        Chunk c1 = chunk(world, 0, 0, true, personalOk, adminOk, personalNoChest, itemNull,
                decodeNull, accountNull, uuidNull, throwing, invalid, nonSign);
        Chunk c2 = chunk(world, 1, 0, false); // enqueued but unloaded before processing -> skipped
        when(world.getLoadedChunks()).thenReturn(new Chunk[]{c1, c2});

        service.resync(initiator, 3);
        driveScan();

        verify(marketApi, times(3)).upsertShop(any()); // personalOk, adminOk, personalNoChest
        verify(marketApi).deactivateShop("world", 9, 64, 9);
        verify(message).send(initiator, "chestshop.resync.started", "chunks", 2);
        verify(message).send(initiator, "chestshop.resync.complete", "upserted", 3, "deactivated", 1);
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
}
