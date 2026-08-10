package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.ItemInfoLines;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import io.paradaux.hibernia.framework.i18n.Message;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Player;
import org.bukkit.entity.TropicalFish;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.AxolotlBucketMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.KnowledgeBookMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.meta.TropicalFishBucketMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.map.MapView;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code /iteminfo} + {@code /shopinfo} formatter. Real MockBukkit items exercise every
 * item-type contributor; the two metas MockBukkit can't populate (map view, potion base data)
 * are mocked so their branches are still driven for real.
 */
class InfoServiceImplTest extends ServerTest {

    private AccountService accounts;
    private EconomyService economy;
    private ItemService items;
    private Message message;
    private SignService signService;
    private ShopBlockService shopBlockService;
    private InventoryService inventoryService;
    private ChestShopConfiguration config;
    private InfoServiceImpl info;
    private CommandSender sender;

    @BeforeEach
    void wire() {
        accounts = mock(AccountService.class);
        economy = mock(EconomyService.class);
        items = mock(ItemService.class);
        message = mock(Message.class);
        signService = mock(SignService.class);
        shopBlockService = mock(ShopBlockService.class);
        inventoryService = mock(InventoryService.class);
        config = TestConfigs.defaults();
        sender = mock(CommandSender.class);
        lenient().when(message.component(anyString(), any(Map.class))).thenReturn(Component.text("x"));
        lenient().when(items.getName(any())).thenReturn("Name");
        info = new InfoServiceImpl(accounts, economy, items, message, config, signService,
                shopBlockService, inventoryService);
    }

    private Set<String> keys(ItemInfoLines lines) {
        return lines.getMessages().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    private Set<String> collect(ItemStack item) {
        return keys(info.collectItemInfo(sender, item));
    }

    // ── guard branches ────────────────────────────────────────────────────────

    @Test
    void metaLessItem_onlyEmitsTheAlwaysOnEnchantmentLine() {
        // AIR has no meta -> every hasItemMeta()/return-early guard takes its false path.
        Set<String> k = collect(new ItemStack(Material.AIR));
        assertThat(k).containsExactly("iteminfo_enchantments"); // the only unconditional line
    }

    @Test
    void plainItem_hasMetaButNoTypedMeta_takesEveryInstanceofFalseBranch() {
        Set<String> k = collect(new ItemStack(Material.STONE));
        assertThat(k).containsExactly("iteminfo_enchantments");
    }

    // ── basic contributors (real items) ───────────────────────────────────────

    @Test
    void repairCost_presentAndZero() {
        ItemStack withCost = new ItemStack(Material.DIAMOND_SWORD);
        Repairable r = (Repairable) withCost.getItemMeta();
        r.setRepairCost(7);
        withCost.setItemMeta((ItemMeta) r);
        assertThat(collect(withCost)).contains("chestshop.iteminfo_repaircost");

        ItemStack zeroCost = new ItemStack(Material.DIAMOND_SWORD); // Repairable, cost 0 -> skipped
        assertThat(collect(zeroCost)).doesNotContain("chestshop.iteminfo_repaircost");
    }

    @Test
    void enchantments_directAndStored() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 3);
        assertThat(collect(sword)).contains("iteminfo_enchantments");

        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta m = (EnchantmentStorageMeta) book.getItemMeta();
        m.addStoredEnchant(Enchantment.SHARPNESS, 2, true);
        book.setItemMeta(m);
        assertThat(collect(book)).contains("iteminfo_enchantments");
    }

    @Test
    void leatherColour() {
        ItemStack armor = new ItemStack(Material.LEATHER_CHESTPLATE);
        LeatherArmorMeta m = (LeatherArmorMeta) armor.getItemMeta();
        m.setColor(Color.fromRGB(0x123456));
        armor.setItemMeta(m);
        assertThat(collect(armor)).contains("chestshop.iteminfo_leather_color");
    }

    @Test
    void knowledgeBook_emptyAndPopulated() {
        ItemStack empty = new ItemStack(Material.KNOWLEDGE_BOOK); // recipes empty -> skipped
        assertThat(collect(empty)).doesNotContain("chestshop.iteminfo_recipes");

        ItemStack withRecipe = new ItemStack(Material.KNOWLEDGE_BOOK);
        KnowledgeBookMeta m = (KnowledgeBookMeta) withRecipe.getItemMeta();
        m.addRecipe(new NamespacedKey("chestshop", "thing"));
        withRecipe.setItemMeta(m);
        assertThat(collect(withRecipe)).contains("chestshop.iteminfo_recipes");
    }

    @Test
    void tropicalFish_withAndWithoutVariant() {
        // MockBukkit's real bucket always reports a variant, so drive the no-variant branch via a mock.
        TropicalFishBucketMeta noVariant = mock(TropicalFishBucketMeta.class);
        when(noVariant.hasVariant()).thenReturn(false);
        assertThat(collect(metaItem(Material.TROPICAL_FISH_BUCKET, noVariant)))
                .doesNotContain("chestshop.iteminfo_tropical_fish");

        ItemStack variant = new ItemStack(Material.TROPICAL_FISH_BUCKET);
        TropicalFishBucketMeta m = (TropicalFishBucketMeta) variant.getItemMeta();
        m.setPattern(TropicalFish.Pattern.FLOPPER);
        m.setBodyColor(org.bukkit.DyeColor.RED);
        m.setPatternColor(org.bukkit.DyeColor.BLUE);
        variant.setItemMeta(m);
        assertThat(collect(variant)).contains("chestshop.iteminfo_tropical_fish");
    }

    @Test
    void writtenBook_withAndWithoutGeneration() {
        ItemStack noGen = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta m = (BookMeta) noGen.getItemMeta();
        m.setTitle("Title");
        m.setAuthor("Author");
        m.setPages("page one");
        noGen.setItemMeta(m);
        Set<String> k1 = collect(noGen);
        assertThat(k1).contains("chestshop.iteminfo_book");
        assertThat(k1).doesNotContain("chestshop.iteminfo_book_generation");

        ItemStack withGen = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta m2 = (BookMeta) withGen.getItemMeta();
        m2.setTitle("Title");
        m2.setAuthor("Author");
        m2.setPages("page one");
        m2.setGeneration(BookMeta.Generation.ORIGINAL);
        withGen.setItemMeta(m2);
        assertThat(collect(withGen)).contains("chestshop.iteminfo_book_generation");
    }

    @Test
    void lore() {
        ItemStack item = new ItemStack(Material.STONE);
        ItemMeta m = item.getItemMeta();
        m.setLore(List.of("line one", "line two"));
        item.setItemMeta(m);
        assertThat(collect(item)).contains("chestshop.iteminfo_lore");
    }

    // ── extended contributors (real items) ────────────────────────────────────

    @Test
    void crossbow_withAndWithoutProjectiles() {
        ItemStack empty = new ItemStack(Material.CROSSBOW);
        assertThat(collect(empty)).doesNotContain("chestshop.iteminfo_crossbow_projectiles");

        ItemStack loaded = new ItemStack(Material.CROSSBOW);
        CrossbowMeta m = (CrossbowMeta) loaded.getItemMeta();
        m.addChargedProjectile(new ItemStack(Material.ARROW));
        loaded.setItemMeta(m);
        Set<String> k = collect(loaded);
        assertThat(k).contains("chestshop.iteminfo_crossbow_projectiles");
        assertThat(k.stream().anyMatch(s -> s.startsWith("crossbow_projectile_"))).isTrue();
    }

    @Test
    void axolotl() {
        ItemStack bucket = new ItemStack(Material.AXOLOTL_BUCKET);
        AxolotlBucketMeta m = (AxolotlBucketMeta) bucket.getItemMeta();
        m.setVariant(Axolotl.Variant.LUCY);
        bucket.setItemMeta(m);
        assertThat(collect(bucket)).contains("chestshop.iteminfo_axolotl_variant");
    }

    @Test
    void bundle() {
        ItemStack bundle = new ItemStack(Material.BUNDLE);
        BundleMeta m = (BundleMeta) bundle.getItemMeta();
        m.addItem(new ItemStack(Material.STONE));
        bundle.setItemMeta(m);
        assertThat(collect(bundle)).contains("chestshop.iteminfo_bundle_items");
    }

    @Test
    void armorTrim_withAndWithout() {
        ItemStack plain = new ItemStack(Material.DIAMOND_CHESTPLATE); // no trim -> skipped
        assertThat(collect(plain)).doesNotContain("chestshop.iteminfo_armor_trim");

        ItemStack trimmed = new ItemStack(Material.DIAMOND_CHESTPLATE);
        ArmorMeta m = (ArmorMeta) trimmed.getItemMeta();
        m.setTrim(new ArmorTrim(TrimMaterial.DIAMOND, TrimPattern.COAST));
        trimmed.setItemMeta(m);
        assertThat(collect(trimmed)).contains("chestshop.iteminfo_armor_trim");
    }

    // ── map (mocked meta: MockBukkit has no map views) ────────────────────────

    /** A mock item carrying a mock meta — for metas MockBukkit can't populate / control. */
    private ItemStack metaItem(Material type, ItemMeta meta) {
        ItemStack it = mock(ItemStack.class);
        lenient().when(it.getType()).thenReturn(type);
        lenient().when(it.hasItemMeta()).thenReturn(true);
        lenient().when(it.getEnchantments()).thenReturn(Map.of());
        lenient().when(it.getItemMeta()).thenReturn(meta);
        return it;
    }

    private ItemStack mapItem(MapMeta meta) {
        return metaItem(Material.FILLED_MAP, meta);
    }

    private MapView mapView(World world, boolean locked) {
        MapView v = mock(MapView.class);
        lenient().when(v.getId()).thenReturn(1);
        lenient().when(v.getCenterX()).thenReturn(10);
        lenient().when(v.getCenterZ()).thenReturn(20);
        lenient().when(v.getWorld()).thenReturn(world);
        lenient().when(v.getScale()).thenReturn(MapView.Scale.NORMAL);
        lenient().when(v.isLocked()).thenReturn(locked);
        return v;
    }

    @Test
    void map_withViewWorldAndLocationName() {
        MapMeta meta = mock(MapMeta.class);
        World world = server.addSimpleWorld("mapworld");
        MapView view = mapView(world, true);
        when(meta.getMapView()).thenReturn(view);
        when(meta.hasLocationName()).thenReturn(true);
        when(meta.getLocationName()).thenReturn("Spawn");

        Set<String> k = collect(mapItem(meta));
        assertThat(k).contains("chestshop.iteminfo_map_view", "chestshop.iteminfo_map_location");
    }

    @Test
    void map_withoutViewOrLocationName() {
        MapMeta meta = mock(MapMeta.class);
        when(meta.getMapView()).thenReturn(null);
        when(meta.hasLocationName()).thenReturn(false);

        Set<String> k = collect(mapItem(meta));
        assertThat(k).doesNotContain("chestshop.iteminfo_map_view", "chestshop.iteminfo_map_location");
    }

    @Test
    void map_viewWithNullWorld_rendersUnknown() {
        MapMeta meta = mock(MapMeta.class);
        MapView view = mapView(null, false); // world null -> "unknown"
        when(meta.getMapView()).thenReturn(view);
        when(meta.hasLocationName()).thenReturn(true);
        when(meta.getLocationName()).thenReturn("Loc");

        assertThat(collect(mapItem(meta))).contains("chestshop.iteminfo_map_view");
    }

    // ── potion (mocked meta: MockBukkit drops upgraded/extended + base type) ───

    private ItemStack potionItem(PotionMeta meta) {
        return metaItem(Material.POTION, meta);
    }

    private PotionData baseData(boolean upgraded, boolean extended) {
        PotionData d = mock(PotionData.class);
        lenient().when(d.getType()).thenReturn(PotionType.SWIFTNESS);
        lenient().when(d.isUpgraded()).thenReturn(upgraded);
        lenient().when(d.isExtended()).thenReturn(extended);
        return d;
    }

    @Test
    void potion_upgraded_withBaseTypeAndEffects() {
        PotionMeta meta = mock(PotionMeta.class);
        PotionData data = baseData(true, false); // -> "II"
        when(meta.getBasePotionData()).thenReturn(data);
        when(meta.getBasePotionType()).thenReturn(PotionType.SWIFTNESS);   // extended: non-null
        List<PotionEffect> effects = List.of(
                new PotionEffect(PotionEffectType.SPEED, 200, 1),
                new PotionEffect(PotionEffectType.JUMP_BOOST, 100, 0));
        when(meta.getCustomEffects()).thenReturn(effects);

        assertThat(collect(potionItem(meta))).contains("iteminfo_potion");
    }

    @Test
    void potion_extended_noBaseTypeNoEffects() {
        PotionMeta meta = mock(PotionMeta.class);
        PotionData data = baseData(false, true); // -> "+"
        when(meta.getBasePotionData()).thenReturn(data);
        when(meta.getBasePotionType()).thenReturn(null);                  // extended: skips base type
        when(meta.getCustomEffects()).thenReturn(List.of());             // extended: empty -> no line

        // basic emits the potion line; extended produces an empty message and adds nothing.
        assertThat(collect(potionItem(meta))).contains("iteminfo_potion");
    }

    @Test
    void potion_plain_nullBaseTypeWithEffect() {
        PotionMeta meta = mock(PotionMeta.class);
        PotionData data = baseData(false, false); // neither
        when(meta.getBasePotionData()).thenReturn(data);
        when(meta.getBasePotionType()).thenReturn(null);
        when(meta.getCustomEffects()).thenReturn(List.of(new PotionEffect(PotionEffectType.SPEED, 60, 0)));

        assertThat(collect(potionItem(meta))).contains("iteminfo_potion");
    }

    // ── sendItemName ──────────────────────────────────────────────────────────

    @Test
    void sendItemName_successAndFailure() {
        when(items.getName(any())).thenReturn("Diamond");
        assertThat(info.sendItemName(sender, new ItemStack(Material.DIAMOND), "chestshop.key")).isTrue();

        when(items.getName(any())).thenThrow(new IllegalArgumentException("bad"));
        assertThat(info.sendItemName(sender, new ItemStack(Material.DIAMOND), "chestshop.key")).isFalse();
    }

    // ── showShopInfo ──────────────────────────────────────────────────────────

    private Sign shopSign(String owner, String qty, String price, String item) {
        World w = server.addSimpleWorld("shopworld-" + System.nanoTime());
        w.getBlockAt(0, 5, 0).setType(Material.OAK_SIGN);
        Sign sign = (Sign) w.getBlockAt(0, 5, 0).getState();
        sign.getSide(Side.FRONT).line(0, Component.text(owner));
        sign.getSide(Side.FRONT).line(1, Component.text(qty));
        sign.getSide(Side.FRONT).line(2, Component.text(price));
        sign.getSide(Side.FRONT).line(3, Component.text(item));
        sign.update();
        return sign;
    }

    @Test
    void showShopInfo_invalidSign() {
        Player p = mock(Player.class);
        Sign sign = shopSign("Owner", "1", "B 10", "Stone");
        when(signService.isValid(sign)).thenReturn(false);
        info.showShopInfo(p, sign);
        // no exception; INVALID message sent and returns early
    }

    @Test
    void showShopInfo_badQuantity() {
        Player p = mock(Player.class);
        Sign sign = shopSign("Owner", "abc", "B 10", "Stone");
        when(signService.isValid(sign)).thenReturn(true);
        info.showShopInfo(p, sign);
    }

    @Test
    void showShopInfo_unknownAccount() {
        Player p = mock(Player.class);
        Sign sign = shopSign("Owner", "1", "B 10", "Stone");
        when(signService.isValid(sign)).thenReturn(true);
        when(accounts.resolveAccount("Owner")).thenReturn(null);
        info.showShopInfo(p, sign);
    }

    @Test
    void showShopInfo_itemUnparseable() {
        Player p = mock(Player.class);
        Sign sign = shopSign("Owner", "1", "B 10", "Bogus");
        when(signService.isValid(sign)).thenReturn(true);
        when(accounts.resolveAccount("Owner")).thenReturn(new Account("Owner", java.util.UUID.randomUUID()));
        when(items.parse(any())).thenReturn(null);
        info.showShopInfo(p, sign);
    }

    @Test
    void showShopInfo_amountBelowOne() {
        Player p = mock(Player.class);
        Sign sign = shopSign("Owner", "0", "B 10", "Stone");
        when(signService.isValid(sign)).thenReturn(true);
        when(accounts.resolveAccount("Owner")).thenReturn(new Account("Owner", java.util.UUID.randomUUID()));
        when(items.parse(any())).thenReturn(new ItemStack(Material.STONE));
        info.showShopInfo(p, sign);
    }

    @Test
    void showShopInfo_full_withStock_bothPrices() {
        Player p = mock(Player.class);
        Sign sign = shopSign("Owner", "5", "B 10 : S 5", "Stone");
        when(signService.isValid(sign)).thenReturn(true);
        when(accounts.resolveAccount("Owner")).thenReturn(new Account("Owner Display", java.util.UUID.randomUUID()));
        ItemStack stone = new ItemStack(Material.STONE);
        when(items.parse(any())).thenReturn(stone);
        Container container = mock(Container.class);
        org.bukkit.inventory.Inventory inv = chest(27);
        when(container.getInventory()).thenReturn(inv);
        when(shopBlockService.findConnectedContainer(sign)).thenReturn(container);
        when(inventoryService.getAmount(stone, inv)).thenReturn(42);
        when(economy.format(any(BigDecimal.class))).thenReturn("$X");

        info.showShopInfo(p, sign);
    }

    @Test
    void showShopInfo_noStock_ownerNameFallback_buyOnly() {
        Player p = mock(Player.class);
        Sign sign = shopSign("Owner", "5", "B 10", "Stone"); // sell price absent -> sell line skipped
        when(signService.isValid(sign)).thenReturn(true);
        when(accounts.resolveAccount("Owner")).thenReturn(new Account(null, "Owner", java.util.UUID.randomUUID())); // name null -> nameLine
        when(items.parse(any())).thenReturn(new ItemStack(Material.STONE));
        when(shopBlockService.findConnectedContainer(sign)).thenReturn(null); // no chest -> infinity
        when(economy.format(any(BigDecimal.class))).thenReturn("$X");

        info.showShopInfo(p, sign);
    }

    @Test
    void showShopInfo_sellOnly() {
        Player p = mock(Player.class);
        Sign sign = shopSign("Owner", "5", "S 5", "Stone"); // buy absent -> buy line skipped
        when(signService.isValid(sign)).thenReturn(true);
        when(accounts.resolveAccount("Owner")).thenReturn(new Account("Owner", java.util.UUID.randomUUID()));
        when(items.parse(any())).thenReturn(new ItemStack(Material.STONE));
        when(shopBlockService.findConnectedContainer(sign)).thenReturn(null);
        when(economy.format(any(BigDecimal.class))).thenReturn("$X");

        info.showShopInfo(p, sign);
    }
}
