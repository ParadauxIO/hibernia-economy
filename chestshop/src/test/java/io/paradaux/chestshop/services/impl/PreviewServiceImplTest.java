package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.services.MarketService;
import io.paradaux.treasury.api.ShopQueryApi;
import io.paradaux.treasury.api.market.ShopResult;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Floating item-preview lifecycle. ItemStack YAML round-trips run on a real MockBukkit server;
 * {@link ItemDisplay} spawning (unimplemented in MockBukkit) is driven through a mock World whose
 * {@code spawn} runs the production spawn-consumer for real against a mock display.
 */
class PreviewServiceImplTest {

    private ServerMock bukkit;
    private JavaPlugin plugin;
    private Server server;
    private World world;
    private ItemDisplay display;
    private MarketService marketService;
    private ShopQueryApi shopQuery;
    private PreviewServiceImpl preview;
    // The scheduler is driven by hand: tasks queue here until a test runs them.
    private final Deque<Runnable> asyncTasks = new ArrayDeque<>();
    private final Deque<Runnable> mainTasks = new ArrayDeque<>();

    @Test
    void configuredPreviewNameIsLiteralAndVisible() {
        var config = mock(io.paradaux.chestshop.model.config.ChestShopConfiguration.class);
        when(config.getPreviewNameFormat()).thenReturn("%world%: %x%, %y%, %z%");
        preview = new PreviewServiceImpl(plugin, marketService,
                new io.paradaux.chestshop.services.ShopDisplayService(plugin, config));
        preview.render("world", 1, 64, 3, new ItemStack(Material.DIAMOND));
        verify(display).customName(net.kyori.adventure.text.Component.text("world: 1, 64, 3"));
        verify(display).setCustomNameVisible(true);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        bukkit = MockBukkit.mock(); // real Bukkit statics for ItemStack construction / YAML
        plugin = mock(JavaPlugin.class);
        server = mock(Server.class);
        world = mock(World.class);
        display = mock(ItemDisplay.class);
        when(plugin.getServer()).thenReturn(server);
        lenient().when(server.getWorld("world")).thenReturn(world);
        lenient().when(server.getWorld("nope")).thenReturn(null);
        lenient().when(display.getTransformation()).thenReturn(
                new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf()));
        lenient().when(world.spawn(any(Location.class), eq(ItemDisplay.class), any(Consumer.class)))
                .thenAnswer(inv -> {
                    Consumer<ItemDisplay> c = inv.getArgument(2);
                    c.accept(display);
                    return display;
                });
        marketService = mock(MarketService.class);
        shopQuery = mock(ShopQueryApi.class);
        lenient().when(marketService.searchEnabled()).thenReturn(true);
        lenient().when(marketService.shopQuery()).thenReturn(shopQuery);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        lenient().when(server.getScheduler()).thenReturn(scheduler);
        lenient().when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            asyncTasks.add(inv.getArgument(1));
            return mock(BukkitTask.class);
        });
        lenient().when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(inv -> {
            mainTasks.add(inv.getArgument(1));
            return mock(BukkitTask.class);
        });
        lenient().when(world.getName()).thenReturn("world");
        lenient().when(world.isChunkLoaded(1, 2)).thenReturn(true);
        preview = new PreviewServiceImpl(plugin, marketService, new io.paradaux.chestshop.services.ShopDisplayService(plugin, new io.paradaux.chestshop.model.config.ChestShopConfiguration()));
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ── render(worldName,...) ─────────────────────────────────────────────────

    @Test
    void render_noSuchWorld_isNoOp() {
        preview.render("nope", 0, 0, 0, new ItemStack(Material.DIAMOND));
        verify(world, never()).spawn(any(Location.class), any(), any(Consumer.class));
    }

    @Test
    void render_spawnsDisplay_andRunsTheSpawnConsumer() {
        preview.render("world", 16, 64, 32, new ItemStack(Material.DIAMOND));
        verify(world).spawn(any(Location.class), eq(ItemDisplay.class), any(Consumer.class));
        verify(display).setItemStack(any());
        verify(display).setPersistent(false);
    }

    @Test
    void render_replacesExistingDisplayAtSamePosition() {
        preview.render("world", 16, 64, 32, new ItemStack(Material.DIAMOND));
        preview.render("world", 16, 64, 32, new ItemStack(Material.STONE)); // destroy old, spawn new
        verify(display, times(1)).remove();            // the first display was destroyed
        verify(world, times(2)).spawn(any(Location.class), eq(ItemDisplay.class), any(Consumer.class));
    }

    @Test
    void render_hidesDisplayForOfflineAwareHiddenPlayers() {
        UUID online = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        Player onlinePlayer = mock(Player.class);
        when(server.getPlayer(online)).thenReturn(onlinePlayer);
        when(server.getPlayer(offline)).thenReturn(null);

        preview.applyPreference(playerWith(online), false); // marks 'online' preview-hidden
        preview.applyPreference(playerWith(offline), false); // marks 'offline' preview-hidden

        preview.render("world", 1, 2, 3, new ItemStack(Material.DIAMOND));

        verify(onlinePlayer).hideEntity(eq(plugin), eq(display)); // online hidden player honoured
        // offline resolves to null and is skipped without error
    }

    private Player playerWith(UUID id) {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(id);
        return p;
    }

    // ── destroy / destroyChunk ────────────────────────────────────────────────

    @Test
    void destroy_unknownChunk_isNoOp() {
        preview.destroy("world", 999, 0, 999); // no chunk map at all
        verify(display, never()).remove();
    }

    @Test
    void destroy_knownChunkButUnknownPosition_isNoOp() {
        preview.render("world", 16, 64, 32, new ItemStack(Material.DIAMOND)); // creates the chunk map
        preview.destroy("world", 17, 64, 33); // same chunk, different position -> no display
        verify(display, never()).remove();
    }

    @Test
    void destroy_removesTheDisplay() {
        preview.render("world", 16, 64, 32, new ItemStack(Material.DIAMOND));
        preview.destroy("world", 16, 64, 32);
        verify(display).remove();
    }

    @Test
    void destroyChunk_removesAll_orNoOpsWhenAbsent() {
        preview.destroyChunk("world", 5, 5); // absent -> no-op branch
        preview.render("world", 16, 64, 32, new ItemStack(Material.DIAMOND));
        preview.destroyChunk("world", 1, 2); // 16>>4=1, 32>>4=2
        verify(display).remove();
    }

    // ── applyPreference ───────────────────────────────────────────────────────

    @Test
    void applyPreference_hideThenShow_togglesEveryDisplay() {
        preview.render("world", 16, 64, 32, new ItemStack(Material.DIAMOND));
        Player p = playerWith(UUID.randomUUID());

        preview.applyPreference(p, false);
        verify(p).hideEntity(plugin, display);

        preview.applyPreference(p, true);
        verify(p).showEntity(plugin, display);
    }

    // ── render(ShopResult) ────────────────────────────────────────────────────

    @Test
    void renderShop_skipsWhenHologramDisabled() {
        ShopResult shop = mock(ShopResult.class);
        when(shop.hologram()).thenReturn(false);
        preview.render(shop);
        verify(world, never()).spawn(any(Location.class), any(), any(Consumer.class));
    }

    @Test
    void renderShop_skipsWhenItemUnresolvable() {
        ShopResult shop = mock(ShopResult.class);
        when(shop.hologram()).thenReturn(true);
        when(shop.material()).thenReturn("NOT_A_MATERIAL"); // toItemStack -> null
        preview.render(shop);
        verify(world, never()).spawn(any(Location.class), any(), any(Consumer.class));
    }

    @Test
    void renderShop_rendersResolvedItem() {
        ShopResult shop = mock(ShopResult.class);
        when(shop.hologram()).thenReturn(true);
        when(shop.material()).thenReturn("DIAMOND");
        when(shop.world()).thenReturn("world");
        when(shop.signX()).thenReturn(16);
        when(shop.signY()).thenReturn(64);
        when(shop.signZ()).thenReturn(32);
        preview.render(shop);
        verify(world).spawn(any(Location.class), eq(ItemDisplay.class), any(Consumer.class));
    }

    // ── destroyAll ────────────────────────────────────────────────────────────

    @Test
    void destroyAll_removesEveryTrackedDisplay_andForgetsThem() {
        preview.render("world", 16, 64, 32, new ItemStack(Material.DIAMOND));
        preview.destroyAll();
        verify(display).remove();

        preview.destroy("world", 16, 64, 32); // no longer tracked -> nothing more to remove
        verify(display, times(1)).remove();
    }

    // ── registry lookups: loadChunk / loadAll / refresh ───────────────────────

    private ShopResult shopAt(int x, int y, int z, boolean hologram) {
        ShopResult shop = mock(ShopResult.class);
        lenient().when(shop.hologram()).thenReturn(hologram);
        lenient().when(shop.itemCustom()).thenReturn(false);
        lenient().when(shop.material()).thenReturn("DIAMOND");
        lenient().when(shop.world()).thenReturn("world");
        lenient().when(shop.signX()).thenReturn(x);
        lenient().when(shop.signY()).thenReturn(y);
        lenient().when(shop.signZ()).thenReturn(z);
        return shop;
    }

    /** What the registry answers for a chunk (the shops are built before the stubbing starts). */
    private void registry(String worldName, int chunkX, int chunkZ, ShopResult... shops) {
        when(shopQuery.shopsInChunk(worldName, chunkX, chunkZ)).thenReturn(List.of(shops));
    }

    /** Run the queued async query, then its main-thread hand-off. */
    private void completeLookup() {
        asyncTasks.poll().run();
        mainTasks.poll().run();
    }

    private void verifySpawns(int count) {
        verify(world, times(count)).spawn(any(Location.class), eq(ItemDisplay.class), any(Consumer.class));
    }

    @Test
    void loadChunk_searchDisabled_queriesNothing() {
        when(marketService.searchEnabled()).thenReturn(false);
        preview.loadChunk("world", 1, 2);
        assertThat(asyncTasks).isEmpty();
    }

    @Test
    void loadChunk_rendersTheChunksShops() {
        registry("world", 1, 2, shopAt(16, 64, 32, true), shopAt(17, 64, 33, true));
        preview.loadChunk("world", 1, 2);
        verifySpawns(0); // nothing until the registry answers
        completeLookup();
        verifySpawns(2);
    }

    @Test
    void loadChunk_leavesAlreadyRenderedShopsAlone() {
        preview.render("world", 16, 64, 32, new ItemStack(Material.DIAMOND));
        registry("world", 1, 2, shopAt(16, 64, 32, true), shopAt(17, 64, 33, true));

        preview.loadChunk("world", 1, 2);
        completeLookup();

        verifySpawns(2);                   // the existing one + the missing one
        verify(display, never()).remove(); // no flicker: the tracked display wasn't replaced
    }

    @Test
    void loadChunk_chunkUnloadedBeforeTheAnswer_rendersNothing() {
        registry("world", 5, 5, shopAt(80, 64, 80, true));
        when(world.isChunkLoaded(5, 5)).thenReturn(false);
        preview.loadChunk("world", 5, 5);
        completeLookup();
        verifySpawns(0);
    }

    @Test
    void loadChunk_worldGoneBeforeTheAnswer_rendersNothing() {
        registry("nope", 1, 2, shopAt(16, 64, 32, true));
        preview.loadChunk("nope", 1, 2);
        completeLookup();
        verifySpawns(0);
    }

    @Test
    void loadChunk_registryFailure_isSwallowed_andLaterLookupsStillRun() {
        ShopResult shop = shopAt(16, 64, 32, true);
        when(shopQuery.shopsInChunk("world", 1, 2)).thenThrow(new RuntimeException("db down"))
                .thenReturn(List.of(shop));
        preview.loadChunk("world", 1, 2);
        completeLookup();
        verifySpawns(0);

        preview.loadChunk("world", 1, 2); // the failed lookup released the in-flight slot
        completeLookup();
        verifySpawns(1);
    }

    @Test
    void lookups_areSerialised_aBurstQueuesBehindTheOneInFlight() {
        // Registry reads share Treasury's connection pool: a burst of chunk loads must not
        // open a query each — one async task at a time, the backlog answered as one batch.
        when(world.isChunkLoaded(3, 3)).thenReturn(true);
        when(world.isChunkLoaded(4, 4)).thenReturn(true);
        registry("world", 1, 2, shopAt(16, 64, 32, true));
        registry("world", 3, 3, shopAt(48, 64, 48, true));
        registry("world", 4, 4, shopAt(64, 64, 64, true));

        preview.loadChunk("world", 1, 2);
        preview.loadChunk("world", 3, 3);
        preview.loadChunk("world", 4, 4);
        preview.loadChunk("world", 4, 4); // duplicate request collapses
        assertThat(asyncTasks).hasSize(1); // only the first is in flight

        completeLookup();
        verifySpawns(1);
        assertThat(asyncTasks).hasSize(1); // the backlog went out as one follow-up task

        completeLookup();
        verifySpawns(3);
        verify(shopQuery, times(1)).shopsInChunk("world", 4, 4);
        assertThat(asyncTasks).isEmpty();
    }

    @Test
    void loadAll_queuesEveryLoadedChunk() {
        Chunk a = mock(Chunk.class);
        when(a.getX()).thenReturn(1);
        when(a.getZ()).thenReturn(2);
        Chunk b = mock(Chunk.class);
        when(b.getX()).thenReturn(3);
        when(b.getZ()).thenReturn(3);
        when(server.getWorlds()).thenReturn(List.of(world));
        when(world.getLoadedChunks()).thenReturn(new Chunk[]{a, b});
        when(world.isChunkLoaded(3, 3)).thenReturn(true);
        registry("world", 1, 2, shopAt(16, 64, 32, true));
        registry("world", 3, 3, shopAt(48, 64, 48, true));

        preview.loadAll();
        completeLookup();
        completeLookup();

        verifySpawns(2);
    }

    @Test
    void refresh_rendersOnlyTheRequestedShop() {
        registry("world", 1, 2, shopAt(16, 64, 32, true), shopAt(17, 64, 33, true));
        preview.refresh("world", 17, 64, 33);
        completeLookup();
        verifySpawns(1);
    }

    @Test
    void refresh_replacesAnExistingDisplay() {
        preview.render("world", 16, 64, 32, new ItemStack(Material.STONE));
        registry("world", 1, 2, shopAt(16, 64, 32, true));

        preview.refresh("world", 16, 64, 32);
        completeLookup();

        verify(display).remove(); // the stale display went
        verifySpawns(2);          // and the current item was rendered in its place
    }

    @Test
    void refresh_dropsTheDisplayWhenTheHologramIsOff_orTheShopIsGone() {
        preview.render("world", 16, 64, 32, new ItemStack(Material.STONE));
        registry("world", 1, 2, shopAt(16, 64, 32, false));
        preview.refresh("world", 16, 64, 32);
        completeLookup();
        verify(display).remove();
        verifySpawns(1); // hologram off -> not re-rendered

        preview.render("world", 16, 64, 32, new ItemStack(Material.STONE));
        registry("world", 1, 2); // no longer registered
        preview.refresh("world", 16, 64, 32);
        completeLookup();
        verify(display, times(2)).remove();
        verifySpawns(2);
    }

    // ── toItemStack (static, all branches) ────────────────────────────────────

    private static String yamlFor(ItemStack item) {
        YamlConfiguration yc = new YamlConfiguration();
        yc.set("item", item);
        return yc.saveToString();
    }

    @Test
    void toItemStack_customWithValidData_returnsStoredItem() {
        ShopResult shop = mock(ShopResult.class);
        when(shop.itemData()).thenReturn(yamlFor(new ItemStack(Material.DIAMOND_SWORD)));
        ItemStack out = PreviewServiceImpl.toItemStack(shop);
        assertThat(out).isNotNull();
        assertThat(out.getType()).isEqualTo(Material.DIAMOND_SWORD);
    }

    @Test
    void toItemStack_customButDataHasNoItemKey_fallsBackToMaterial() {
        ShopResult shop = mock(ShopResult.class);
        when(shop.itemData()).thenReturn("other: 1"); // loads, but no "item" key -> null
        when(shop.material()).thenReturn("STONE");
        assertThat(PreviewServiceImpl.toItemStack(shop).getType()).isEqualTo(Material.STONE);
    }

    @Test
    void toItemStack_customButBrokenYaml_fallsBackToMaterial() {
        ShopResult shop = mock(ShopResult.class);
        when(shop.itemData()).thenReturn("::: not : valid : yaml :::"); // loadFromString throws
        when(shop.material()).thenReturn("STONE");
        assertThat(PreviewServiceImpl.toItemStack(shop).getType()).isEqualTo(Material.STONE);
    }

    @Test
    void toItemStack_customButNullData_usesMaterial() {
        ShopResult shop = mock(ShopResult.class);
        when(shop.itemData()).thenReturn(null);
        when(shop.material()).thenReturn("STONE");
        assertThat(PreviewServiceImpl.toItemStack(shop).getType()).isEqualTo(Material.STONE);
    }

    @Test
    void toItemStack_vanilla_usesMaterial() {
        ShopResult shop = mock(ShopResult.class);
        when(shop.material()).thenReturn("STONE");
        assertThat(PreviewServiceImpl.toItemStack(shop).getType()).isEqualTo(Material.STONE);
    }

    @Test
    void toItemStack_invalidMaterial_returnsNull() {
        ShopResult shop = mock(ShopResult.class);
        when(shop.material()).thenReturn("NOPE");
        assertThat(PreviewServiceImpl.toItemStack(shop)).isNull();
    }
}
