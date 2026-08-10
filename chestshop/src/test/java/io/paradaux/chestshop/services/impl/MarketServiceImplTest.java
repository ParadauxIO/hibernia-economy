package io.paradaux.chestshop.services.impl;

import io.paradaux.business.api.BusinessApi;
import io.paradaux.business.api.FirmApi;
import io.paradaux.business.model.Firm;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.ItemCodeService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.utils.MaterialUtil;
import io.paradaux.treasury.api.MarketApi;
import io.paradaux.treasury.api.ShopQueryApi;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.api.market.ChestShopSaleRecord;
import io.paradaux.treasury.api.market.ChestShopShopRecord;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;

import io.paradaux.chestshop.support.ServerTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The market DTO builder + Treasury API holder. Real MockBukkit signs, items and inventories;
 * only the Treasury/Business boundary and the two ChestShop item services are mocked.
 */
class MarketServiceImplTest extends ServerTest {

    private ItemService items;
    private ItemCodeService itemCodes;
    private InventoryService inventoryService;
    private MarketServiceImpl market;
    private Plugin plugin;
    private WorldMock world;

    @BeforeEach
    void wire() {
        items = mock(ItemService.class);
        itemCodes = mock(ItemCodeService.class);
        inventoryService = mock(InventoryService.class);
        market = new MarketServiceImpl(items, itemCodes, inventoryService);
        plugin = org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin("Market");
        world = server.addSimpleWorld("world");
    }

    private <T> void register(Class<T> type, T provider) {
        server.getServicesManager().register(type, provider, plugin, ServicePriority.Normal);
    }

    /** A real standing-sign at (x,y,z) with the four given lines. */
    private Sign signAt(int x, int y, int z, String owner, String qty, String price, String item) {
        world.getBlockAt(x, y, z).setType(Material.OAK_SIGN);
        Sign sign = (Sign) world.getBlockAt(x, y, z).getState();
        sign.getSide(Side.FRONT).line(0, Component.text(owner));
        sign.getSide(Side.FRONT).line(1, Component.text(qty));
        sign.getSide(Side.FRONT).line(2, Component.text(price));
        sign.getSide(Side.FRONT).line(3, Component.text(item));
        sign.update();
        return sign;
    }

    // ── init / enabled / searchEnabled ────────────────────────────────────────

    @Test
    void init_allApisRegistered_enablesEverything() {
        register(MarketApi.class, mock(MarketApi.class));
        register(ShopQueryApi.class, mock(ShopQueryApi.class));
        register(TreasuryApi.class, mock(TreasuryApi.class));
        register(BusinessApi.class, mock(BusinessApi.class));

        market.init();

        assertThat(market.enabled()).isTrue();
        assertThat(market.searchEnabled()).isTrue();
        assertThat(market.market()).isNotNull();
        assertThat(market.shopQuery()).isNotNull();
        assertThat(market.treasury()).isNotNull();
        assertThat(market.business()).isNotNull();
    }

    @Test
    void init_marketWithoutTreasury_isDisabled() {
        register(MarketApi.class, mock(MarketApi.class)); // market present, treasury absent
        market.init();
        assertThat(market.enabled()).isFalse();       // market != null but treasury == null
        assertThat(market.searchEnabled()).isFalse(); // shopQuery absent
    }

    @Test
    void init_nothingRegistered_isDisabled() {
        market.init(); // server up, no providers -> every getRegistration returns null
        assertThat(market.enabled()).isFalse();        // market == null short-circuits
        assertThat(market.searchEnabled()).isFalse();
    }

    @Test
    void init_withoutAServer_swallowsAndStaysDisabled() {
        org.mockbukkit.mockbukkit.MockBukkit.unmock(); // Bukkit statics now throw -> load() catch
        try {
            MarketServiceImpl m = new MarketServiceImpl(items, itemCodes, inventoryService);
            m.init();
            assertThat(m.enabled()).isFalse();
            assertThat(m.searchEnabled()).isFalse();
        } finally {
            server = org.mockbukkit.mockbukkit.MockBukkit.mock(); // restore for @AfterEach
        }
    }

    // ── ownerFromUuid / classify ──────────────────────────────────────────────

    private Account acc(int id, AccountType type, UUID owner) {
        Account a = new Account();
        a.setAccountId(id);
        a.setAccountType(type);
        a.setOwnerUuid(owner);
        return a;
    }

    @Test
    void owner_adminShop_orNullUuid_isAdmin() {
        register(TreasuryApi.class, mock(TreasuryApi.class));
        market.init();

        MarketService.Owner byFlag = market.ownerFromUuid(UUID.randomUUID(), true);
        assertThat(byFlag.admin()).isTrue();

        MarketService.Owner byNull = market.ownerFromUuid(null, false);
        assertThat(byNull.admin()).isTrue();
    }

    @Test
    void owner_personalUnknownToTreasury_isUnresolved() {
        TreasuryApi treasury = mock(TreasuryApi.class);
        UUID uuid = UUID.randomUUID();
        when(treasury.getAccountByUUID(uuid)).thenReturn(null);
        register(TreasuryApi.class, treasury);
        market.init();

        MarketService.Owner owner = market.ownerFromUuid(uuid, false);
        assertThat(owner.admin()).isFalse();
        assertThat(owner.accountId()).isNull();
    }

    @Test
    void owner_personalAccount_classifiesWithOwnerUuid() {
        TreasuryApi treasury = mock(TreasuryApi.class);
        UUID uuid = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        when(treasury.getAccountByUUID(uuid)).thenReturn(acc(12, AccountType.PERSONAL, ownerUuid));
        when(treasury.getAccountById(12)).thenReturn(acc(12, AccountType.PERSONAL, ownerUuid));
        register(TreasuryApi.class, treasury);
        market.init();

        MarketService.Owner owner = market.ownerFromUuid(uuid, false);
        assertThat(owner.accountId()).isEqualTo(12);
        assertThat(owner.type()).isEqualTo("PERSONAL");
        assertThat(owner.ownerUuid()).isEqualTo(ownerUuid);
        assertThat(owner.firmId()).isNull();
    }

    @Test
    void owner_businessUuid_missingAccount_isUnresolvedButHasId() {
        TreasuryApi treasury = mock(TreasuryApi.class);
        when(treasury.getAccountById(77)).thenReturn(null); // classify: account gone
        register(TreasuryApi.class, treasury);
        market.init();

        UUID businessUuid = new UUID(MarketServiceImpl.BUSINESS_UUID_MSB, 77L);
        MarketService.Owner owner = market.ownerFromUuid(businessUuid, false);
        assertThat(owner.accountId()).isEqualTo(77);
        assertThat(owner.type()).isNull();
        assertThat(owner.admin()).isFalse();
    }

    @Test
    void owner_businessAccount_withFirm_setsFirmId() {
        TreasuryApi treasury = mock(TreasuryApi.class);
        when(treasury.getAccountById(13)).thenReturn(acc(13, AccountType.BUSINESS, null));
        BusinessApi business = mock(BusinessApi.class);
        FirmApi firms = mock(FirmApi.class);
        Firm firm = mock(Firm.class);
        when(firm.getFirmId()).thenReturn(42);
        when(firms.getFirmByAccountId(13)).thenReturn(firm);
        when(business.firms()).thenReturn(firms);
        register(TreasuryApi.class, treasury);
        register(BusinessApi.class, business);
        market.init();

        UUID businessUuid = new UUID(MarketServiceImpl.BUSINESS_UUID_MSB, 13L);
        MarketService.Owner owner = market.ownerFromUuid(businessUuid, false);
        assertThat(owner.type()).isEqualTo("BUSINESS");
        assertThat(owner.firmId()).isEqualTo(42);
    }

    @Test
    void owner_businessAccount_noFirmRow_leavesFirmIdNull() {
        TreasuryApi treasury = mock(TreasuryApi.class);
        when(treasury.getAccountById(14)).thenReturn(acc(14, AccountType.BUSINESS, null));
        BusinessApi business = mock(BusinessApi.class);
        FirmApi firms = mock(FirmApi.class);
        when(firms.getFirmByAccountId(14)).thenReturn(null);
        when(business.firms()).thenReturn(firms);
        register(TreasuryApi.class, treasury);
        register(BusinessApi.class, business);
        market.init();

        MarketService.Owner owner = market.ownerFromUuid(new UUID(MarketServiceImpl.BUSINESS_UUID_MSB, 14L), false);
        assertThat(owner.type()).isEqualTo("BUSINESS");
        assertThat(owner.firmId()).isNull();
    }

    @Test
    void owner_businessAccount_withoutBusinessApi_leavesFirmIdNull() {
        TreasuryApi treasury = mock(TreasuryApi.class);
        when(treasury.getAccountById(15)).thenReturn(acc(15, AccountType.BUSINESS, null));
        register(TreasuryApi.class, treasury); // no BusinessApi registered
        market.init();

        MarketService.Owner owner = market.ownerFromUuid(new UUID(MarketServiceImpl.BUSINESS_UUID_MSB, 15L), false);
        assertThat(owner.type()).isEqualTo("BUSINESS");
        assertThat(owner.firmId()).isNull();
    }

    @Test
    void owner_accountWithNullType_andGovernment() {
        TreasuryApi treasury = mock(TreasuryApi.class);
        when(treasury.getAccountById(16)).thenReturn(acc(16, null, UUID.randomUUID())); // null type
        when(treasury.getAccountById(17)).thenReturn(acc(17, AccountType.GOVERNMENT, UUID.randomUUID()));
        register(TreasuryApi.class, treasury);
        market.init();

        MarketService.Owner nullType = market.ownerFromUuid(new UUID(MarketServiceImpl.BUSINESS_UUID_MSB, 16L), false);
        assertThat(nullType.type()).isNull();
        assertThat(nullType.ownerUuid()).isNull(); // not PERSONAL -> no owner uuid

        MarketService.Owner gov = market.ownerFromUuid(new UUID(MarketServiceImpl.BUSINESS_UUID_MSB, 17L), false);
        assertThat(gov.type()).isEqualTo("GOVERNMENT");
        assertThat(gov.ownerUuid()).isNull();
        assertThat(gov.firmId()).isNull();
    }

    // ── stock / capacity / totalAmount ────────────────────────────────────────

    @Test
    void stockAndCapacity_delegateToInventory_orZeroNullForNoInventory() {
        ItemStack diamond = item(Material.DIAMOND, 1);
        Inventory inv = chest(27);
        when(inventoryService.getAmount(diamond, inv)).thenReturn(9);
        when(inventoryService.getRemainingCapacity(diamond, inv)).thenReturn(55);

        assertThat(market.stockOf(diamond, inv)).isEqualTo(9);
        assertThat(market.capacityOf(diamond, inv)).isEqualTo(55);
        assertThat(market.stockOf(diamond, null)).isZero();
        assertThat(market.capacityOf(diamond, null)).isNull();
    }

    @Test
    void totalAmount_sumsSkippingNulls() {
        assertThat(market.totalAmount(new ItemStack[]{item(Material.DIAMOND, 5), null, item(Material.DIAMOND, 3)}))
                .isEqualTo(8);
    }

    // ── sale() ────────────────────────────────────────────────────────────────

    private MarketService.Owner personalOwner() {
        return new MarketService.Owner(5, "PERSONAL", null, UUID.randomUUID(), false);
    }

    @Test
    void sale_personalOwner_customItemWithDisplayName() {
        ItemStack sword = item(Material.DIAMOND_SWORD, 1);
        ItemMeta meta = sword.getItemMeta();
        meta.displayName(Component.text("Excalibur"));
        sword.setItemMeta(meta);
        when(items.getName(sword, 0)).thenReturn("nexo:excalibur#01");
        when(itemCodes.encode(sword, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("DIAMOND_SWORD");

        Sign sign = signAt(0, 5, 0, "Owner", "1", "B 10", "Diamond Sword");
        ChestShopSaleRecord rec = market.sale(sign, sword, 4, UUID.randomUUID(), personalOwner(),
                BigDecimal.valueOf(40), BigDecimal.valueOf(2), "BUY", 100, 7L);

        assertThat(rec.material()).isEqualTo("DIAMOND_SWORD");
        assertThat(rec.itemKey()).isEqualTo("nexo:excalibur#01");
        assertThat(rec.itemName()).isEqualTo("Excalibur");
        assertThat(rec.itemCustom()).isTrue();
        assertThat(rec.itemData()).isNotNull();
        assertThat(rec.unitPrice()).isEqualByComparingTo("10");
        assertThat(rec.taxAmount()).isEqualByComparingTo("2");
        assertThat(rec.shopStock()).isEqualTo(100);
        assertThat(rec.world()).isEqualTo("world");
    }

    @Test
    void sale_zeroQuantity_usesTotalAsUnit_nullTaxBecomesZero_adminHidesStock() {
        ItemStack stone = noMetaItem(Material.STONE);
        when(items.getName(stone, 0)).thenReturn("STONE");
        when(itemCodes.encode(stone, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("STONE");

        Sign sign = signAt(1, 5, 1, "Owner", "1", "B 10", "Stone");
        MarketService.Owner admin = new MarketService.Owner(null, null, null, null, true);
        ChestShopSaleRecord rec = market.sale(sign, stone, 0, UUID.randomUUID(), admin,
                BigDecimal.valueOf(10), null, "SELL", 50, null);

        assertThat(rec.unitPrice()).isEqualByComparingTo("10"); // quantity 0 -> unit = total
        assertThat(rec.taxAmount()).isEqualByComparingTo("0");  // null tax -> ZERO
        assertThat(rec.shopStock()).isNull();                   // admin -> stock hidden
        assertThat(rec.itemCustom()).isFalse();
        assertThat(rec.itemData()).isNull();                    // not custom -> no data
    }

    @Test
    void sale_nullWorldLocation_yieldsNullWorldName() {
        ItemStack stone = item(Material.STONE, 1);
        when(items.getName(stone, 0)).thenReturn("STONE");
        when(itemCodes.encode(stone, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("STONE");

        Sign sign = mock(Sign.class);
        when(sign.getLocation()).thenReturn(new Location(null, 3, 4, 5));

        ChestShopSaleRecord rec = market.sale(sign, stone, 2, UUID.randomUUID(), personalOwner(),
                BigDecimal.valueOf(10), BigDecimal.ZERO, "BUY", 1, 1L);
        assertThat(rec.world()).isNull();
        assertThat(rec.signX()).isEqualTo(3);
    }

    // ── shop() ────────────────────────────────────────────────────────────────

    @Test
    void shop_personalOwner_realSign_populatesEverything() {
        ItemStack stone = item(Material.STONE, 1);
        when(items.getName(stone, 0)).thenReturn("STONE");
        when(itemCodes.encode(stone, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("STONE");

        Sign sign = signAt(2, 5, 2, "Owner", "16", "B 10 : S 5", "Stone");
        ChestShopShopRecord rec = market.shop(sign, stone, personalOwner(), 32, 100);

        assertThat(rec.world()).isEqualTo("world");
        assertThat(rec.batchQty()).isEqualTo(16);
        assertThat(rec.buyPrice()).isEqualByComparingTo("10");
        assertThat(rec.sellPrice()).isEqualByComparingTo("5");
        assertThat(rec.currentStock()).isEqualTo(32);
        assertThat(rec.estimatedCapacity()).isEqualTo(100);
        assertThat(rec.worldUuid()).isNotNull();
    }

    @Test
    void shop_adminOwner_hidesStockAndCapacity_sellOnlyPrice() {
        ItemStack stone = item(Material.STONE, 1);
        when(items.getName(stone, 0)).thenReturn("STONE");
        when(itemCodes.encode(stone, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("STONE");

        Sign sign = signAt(3, 5, 3, "Owner", "1", "S 5", "Stone"); // sell only -> buyPrice null
        MarketService.Owner admin = new MarketService.Owner(null, null, null, null, true);
        ChestShopShopRecord rec = market.shop(sign, stone, admin, 32, 100);

        assertThat(rec.buyPrice()).isNull();          // no buy offered -> nonNegativeOrNull(NO_PRICE)
        assertThat(rec.sellPrice()).isEqualByComparingTo("5");
        assertThat(rec.currentStock()).isNull();      // admin
        assertThat(rec.estimatedCapacity()).isNull(); // admin
    }

    @Test
    void shop_buyOnlyPrice_nullsSellPrice() {
        ItemStack stone = item(Material.STONE, 1);
        when(items.getName(stone, 0)).thenReturn("STONE");
        when(itemCodes.encode(stone, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("STONE");

        Sign sign = signAt(6, 5, 6, "Owner", "1", "B 10", "Stone");
        ChestShopShopRecord rec = market.shop(sign, stone, personalOwner(), 1, 1);
        assertThat(rec.buyPrice()).isEqualByComparingTo("10");
        assertThat(rec.sellPrice()).isNull();
    }

    @Test
    void shop_badQuantityLine_fallsBackToItemAmount() {
        ItemStack stone = item(Material.STONE, 3);
        when(items.getName(stone, 0)).thenReturn("STONE");
        when(itemCodes.encode(stone, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("STONE");

        Sign sign = signAt(4, 5, 4, "Owner", "abc", "B 10", "Stone"); // "abc" -> getQuantity throws
        ChestShopShopRecord rec = market.shop(sign, stone, personalOwner(), 1, 1);
        assertThat(rec.batchQty()).isEqualTo(3); // max(1, item amount)
    }

    @Test
    void shop_nullWorldLocation_nullsWorldNameAndUuid() {
        ItemStack stone = item(Material.STONE, 1);
        when(items.getName(stone, 0)).thenReturn("STONE");
        when(itemCodes.encode(stone, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("STONE");

        Sign sign = mock(Sign.class);
        when(sign.getLocation()).thenReturn(new Location(null, 10, 20, 30));
        when(sign.getLines()).thenReturn(new String[]{"Owner", "8", "B 10", "Stone"});
        org.bukkit.block.sign.SignSide side = mock(org.bukkit.block.sign.SignSide.class);
        when(sign.getSide(Side.FRONT)).thenReturn(side);
        when(side.line(2)).thenReturn(Component.text("B 10"));

        ChestShopShopRecord rec = market.shop(sign, stone, personalOwner(), 1, 1);
        assertThat(rec.world()).isNull();
        assertThat(rec.worldUuid()).isNull();
        assertThat(rec.batchQty()).isEqualTo(8);
    }

    // ── item-identity edge branches (via sale) ────────────────────────────────

    private ChestShopSaleRecord saleOf(ItemStack it) {
        Sign sign = signAt(0, 6, 0, "Owner", "1", "B 10", "x");
        return market.sale(sign, it, 1, UUID.randomUUID(), personalOwner(),
                BigDecimal.TEN, BigDecimal.ZERO, "BUY", 1, 1L);
    }

    /** A meta-less item — MockBukkit's real ItemStacks always report {@code hasItemMeta()}. */
    private ItemStack noMetaItem(Material m) {
        ItemStack it = mock(ItemStack.class);
        lenient().when(it.getType()).thenReturn(m);
        lenient().when(it.hasItemMeta()).thenReturn(false);
        return it;
    }

    @Test
    void identity_customNoMeta_namesFromCanonicalCode() {
        ItemStack stone = item(Material.STONE, 1); // no meta
        when(items.getName(stone, 0)).thenReturn("nexo:ruby_sword#02");
        when(itemCodes.encode(stone, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("STONE");
        ChestShopSaleRecord rec = saleOf(stone);
        assertThat(rec.itemCustom()).isTrue();
        assertThat(rec.itemName()).isEqualTo("Ruby Sword"); // pretty(canonical), colon+hash stripped
        assertThat(rec.itemKey()).isEqualTo("nexo:ruby_sword#02");
    }

    @Test
    void identity_vanilla_prettyFromType_noData() {
        ItemStack stone = noMetaItem(Material.STONE);
        when(items.getName(stone, 0)).thenReturn("STONE");
        when(itemCodes.encode(stone, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("STONE");
        ChestShopSaleRecord rec = saleOf(stone);
        assertThat(rec.itemCustom()).isFalse();
        assertThat(rec.itemName()).isEqualTo("Stone");
        assertThat(rec.itemData()).isNull();
    }

    @Test
    void identity_canonicalThrows_vanillaHasHash_isCustom() {
        ItemStack stone = item(Material.STONE, 1);
        when(items.getName(stone, 0)).thenThrow(new RuntimeException("no resolver")); // canonicalCode -> null
        when(itemCodes.encode(stone, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("STONE#3");
        ChestShopSaleRecord rec = saleOf(stone);
        assertThat(rec.itemCustom()).isTrue();      // vanilla contains '#'
        assertThat(rec.itemKey()).isEqualTo("STONE#3"); // code null -> encode
        assertThat(rec.itemName()).isEqualTo("Stone");  // canonical null -> pretty(type)
    }

    @Test
    void identity_metaButSameCode_isCustomByMeta() {
        ItemStack diamond = item(Material.DIAMOND, 1);
        ItemMeta meta = diamond.getItemMeta();
        meta.displayName(Component.text("   ")); // blank -> display-name path skipped
        diamond.setItemMeta(meta);
        when(items.getName(diamond, 0)).thenReturn("DIAMOND");
        when(itemCodes.encode(diamond, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("DIAMOND");
        ChestShopSaleRecord rec = saleOf(diamond);
        assertThat(rec.itemCustom()).isTrue();   // canonical==vanilla, no '#', but hasItemMeta
        assertThat(rec.itemName()).isEqualTo("Diamond");
    }

    @Test
    void identity_nullVanillaCode_notCustom() {
        ItemStack stone = noMetaItem(Material.STONE);
        when(items.getName(stone, 0)).thenReturn("STONE");
        when(itemCodes.encode(stone, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn(null); // vanilla null
        ChestShopSaleRecord rec = saleOf(stone);
        assertThat(rec.itemCustom()).isFalse();
        assertThat(rec.itemKey()).isEqualTo("STONE");
    }

    @Test
    void identity_bothCodesNull_noMeta_notCustom() {
        ItemStack stone = noMetaItem(Material.STONE);
        when(items.getName(stone, 0)).thenThrow(new RuntimeException("x")); // canonical null
        when(itemCodes.encode(stone, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn(null); // vanilla null
        ChestShopSaleRecord rec = saleOf(stone);
        assertThat(rec.itemCustom()).isFalse();
        assertThat(rec.itemKey()).isNull();
        assertThat(rec.itemName()).isEqualTo("Stone");
    }

    @Test
    void identity_customWithBlankCanonical_namesFromType() {
        ItemStack stone = noMetaItem(Material.STONE);
        when(items.getName(stone, 0)).thenReturn("   ");   // canonical present but blank
        when(itemCodes.encode(stone, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("STONE#1");
        ChestShopSaleRecord rec = saleOf(stone);
        assertThat(rec.itemCustom()).isTrue();
        assertThat(rec.itemName()).isEqualTo("Stone"); // blank canonical -> pretty(type)
    }

    @Test
    void identity_prettyWithLeadingSeparator_andAllBlankWords() {
        // Leading '_' after the colon yields an empty split word (skipped);
        // "mymod:_" reduces to no words at all -> pretty returns the raw string.
        ItemStack a = noMetaItem(Material.STONE);
        when(items.getName(a, 0)).thenReturn("mymod:_sword");
        when(itemCodes.encode(a, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("STONE");
        assertThat(saleOf(a).itemName()).isEqualTo("Sword");

        ItemStack b = noMetaItem(Material.STONE);
        when(items.getName(b, 0)).thenReturn("mymod:_");
        when(itemCodes.encode(b, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("STONE");
        assertThat(saleOf(b).itemName()).isEqualTo("mymod:_"); // no usable words -> raw
    }

    @Test
    void identity_itemDataSerializationFailure_yieldsNullData() {
        ItemStack broken = mock(ItemStack.class);
        when(broken.getType()).thenReturn(Material.STONE);
        when(broken.hasItemMeta()).thenReturn(true);
        when(broken.getItemMeta()).thenReturn(null); // no display name path
        when(broken.serialize()).thenThrow(new RuntimeException("cannot serialize"));
        when(items.getName(broken, 0)).thenReturn("nexo:x#1");
        when(itemCodes.encode(broken, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("STONE");
        ChestShopSaleRecord rec = saleOf(broken);
        assertThat(rec.itemCustom()).isTrue();
        assertThat(rec.itemData()).isNull(); // saveToString threw -> caught -> null
    }

    // ── private null-guard helpers (unreachable through the public API) ────────

    @Test
    void pretty_and_nonNegative_tolerateNull() throws Exception {
        Method pretty = MarketServiceImpl.class.getDeclaredMethod("pretty", String.class);
        pretty.setAccessible(true);
        assertThat(pretty.invoke(null, (Object) null)).isNull();

        Method nn = MarketServiceImpl.class.getDeclaredMethod("nonNegativeOrNull", BigDecimal.class);
        nn.setAccessible(true);
        assertThat(nn.invoke(null, (Object) null)).isNull();
    }
}
