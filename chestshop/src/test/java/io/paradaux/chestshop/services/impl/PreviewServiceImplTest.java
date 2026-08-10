package io.paradaux.chestshop.services.impl;

import io.paradaux.treasury.api.market.ShopResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

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
    private PreviewServiceImpl preview;

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
        preview = new PreviewServiceImpl(plugin);
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
        when(shop.itemCustom()).thenReturn(false);
        when(shop.material()).thenReturn("NOT_A_MATERIAL"); // toItemStack -> null
        preview.render(shop);
        verify(world, never()).spawn(any(Location.class), any(), any(Consumer.class));
    }

    @Test
    void renderShop_rendersResolvedItem() {
        ShopResult shop = mock(ShopResult.class);
        when(shop.hologram()).thenReturn(true);
        when(shop.itemCustom()).thenReturn(false);
        when(shop.material()).thenReturn("DIAMOND");
        when(shop.world()).thenReturn("world");
        when(shop.signX()).thenReturn(16);
        when(shop.signY()).thenReturn(64);
        when(shop.signZ()).thenReturn(32);
        preview.render(shop);
        verify(world).spawn(any(Location.class), eq(ItemDisplay.class), any(Consumer.class));
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
        when(shop.itemCustom()).thenReturn(true);
        when(shop.itemData()).thenReturn(yamlFor(new ItemStack(Material.DIAMOND_SWORD)));
        ItemStack out = PreviewServiceImpl.toItemStack(shop);
        assertThat(out).isNotNull();
        assertThat(out.getType()).isEqualTo(Material.DIAMOND_SWORD);
    }

    @Test
    void toItemStack_customButDataHasNoItemKey_fallsBackToMaterial() {
        ShopResult shop = mock(ShopResult.class);
        when(shop.itemCustom()).thenReturn(true);
        when(shop.itemData()).thenReturn("other: 1"); // loads, but no "item" key -> null
        when(shop.material()).thenReturn("STONE");
        assertThat(PreviewServiceImpl.toItemStack(shop).getType()).isEqualTo(Material.STONE);
    }

    @Test
    void toItemStack_customButBrokenYaml_fallsBackToMaterial() {
        ShopResult shop = mock(ShopResult.class);
        when(shop.itemCustom()).thenReturn(true);
        when(shop.itemData()).thenReturn("::: not : valid : yaml :::"); // loadFromString throws
        when(shop.material()).thenReturn("STONE");
        assertThat(PreviewServiceImpl.toItemStack(shop).getType()).isEqualTo(Material.STONE);
    }

    @Test
    void toItemStack_customButNullData_usesMaterial() {
        ShopResult shop = mock(ShopResult.class);
        when(shop.itemCustom()).thenReturn(true);
        when(shop.itemData()).thenReturn(null);
        when(shop.material()).thenReturn("STONE");
        assertThat(PreviewServiceImpl.toItemStack(shop).getType()).isEqualTo(Material.STONE);
    }

    @Test
    void toItemStack_vanilla_usesMaterial() {
        ShopResult shop = mock(ShopResult.class);
        when(shop.itemCustom()).thenReturn(false);
        when(shop.material()).thenReturn("STONE");
        assertThat(PreviewServiceImpl.toItemStack(shop).getType()).isEqualTo(Material.STONE);
    }

    @Test
    void toItemStack_invalidMaterial_returnsNull() {
        ShopResult shop = mock(ShopResult.class);
        when(shop.itemCustom()).thenReturn(false);
        when(shop.material()).thenReturn("NOPE");
        assertThat(PreviewServiceImpl.toItemStack(shop)).isNull();
    }
}
