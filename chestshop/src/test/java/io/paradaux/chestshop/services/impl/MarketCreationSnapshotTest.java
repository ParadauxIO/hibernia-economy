package io.paradaux.chestshop.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.ItemCodeService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.utils.MaterialUtil;
import io.paradaux.treasury.api.MarketApi;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.api.market.ChestShopShopRecord;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.BeforeEach;
import org.mockbukkit.mockbukkit.world.WorldMock;

/**
 * The market DTO builder + Treasury API holder. Real MockBukkit signs, items and inventories;
 * only the Treasury/Business boundary and the two ChestShop item services are mocked.
 */
class MarketCreationSnapshotTest extends ServerTest {

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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1,B 1", "'',''", "64,B 40"})
    void creationIndexesPreparedTextBeforeTheBlockSnapshotChanges(String oldQuantity, String oldPrice) {
        MarketApi api = mock(MarketApi.class);
        register(MarketApi.class, api);
        register(TreasuryApi.class, mock(TreasuryApi.class));
        market.init();
        MarketServiceImpl spy = org.mockito.Mockito.spy(market);
        MarketService.Owner owner = new MarketService.Owner(1, "PERSONAL", null, UUID.randomUUID(), false);
        org.mockito.Mockito.doReturn(owner).when(spy).ownerFromUuid(null, false);
        ItemStack stone = new ItemStack(Material.STONE, 1);
        when(items.parse("STONE")).thenReturn(stone);
        when(items.getName(stone, 0)).thenReturn("STONE");
        when(itemCodes.encode(stone, MaterialUtil.MAXIMUM_SIGN_WIDTH)).thenReturn("STONE");
        Sign oldSnapshot = signAt(2, 5, 2, "Owner", oldQuantity, oldPrice, "STONE");
        String[] eventText = {"Owner", "Q 64 : C 0", "B 40", "STONE"};
        var sync = new MarketSyncServiceImpl(
                mock(org.bukkit.plugin.java.JavaPlugin.class),
                spy,
                items,
                mock(io.paradaux.chestshop.services.SignService.class),
                mock(io.paradaux.chestshop.services.ShopBlockService.class),
                mock(io.paradaux.chestshop.services.AccountService.class),
                mock(io.paradaux.chestshop.services.PreviewService.class),
                mock(io.paradaux.chestshop.services.ShopStockPublisher.class));
        sync.onShopCreated(new io.paradaux.chestshop.model.CreatedShop(
                mock(org.bukkit.entity.Player.class), oldSnapshot, null, eventText, null));
        var captured = org.mockito.ArgumentCaptor.forClass(ChestShopShopRecord.class);
        org.mockito.Mockito.verify(api).upsertShop(captured.capture());
        assertThat(captured.getValue().batchQty()).isEqualTo(64);
        assertThat(captured.getValue().buyPrice()).isEqualByComparingTo("40");
    }
}
