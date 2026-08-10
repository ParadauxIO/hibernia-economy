package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.CreatedShop;
import io.paradaux.chestshop.model.DestroyedShop;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.services.ItemCodeService;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.treasury.api.MarketApi;
import io.paradaux.treasury.api.market.ChestShopSaleRecord;
import io.paradaux.treasury.api.market.ChestShopShopRecord;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito coverage of {@link MarketSyncServiceImpl}. Every collaborator is a mock; the
 * class only threads its inputs to {@link MarketService}/{@link MarketApi}, so no live Bukkit
 * server is needed. Each of the four hooks exercises the disabled short-circuit, its branches,
 * and its guarding catch (a collaborator throwing a {@link Throwable}).
 */
class MarketSyncServiceImplTest {

    private MarketService marketService;
    private ItemCodeService itemCodes;
    private SignService signService;
    private ShopBlockService shopBlockService;
    private MarketApi market;
    private MarketSyncServiceImpl sync;

    @BeforeEach
    void wire() {
        marketService = mock(MarketService.class);
        itemCodes = mock(ItemCodeService.class);
        signService = mock(SignService.class);
        shopBlockService = mock(ShopBlockService.class);
        market = mock(MarketApi.class);
        sync = new MarketSyncServiceImpl(marketService, itemCodes, signService, shopBlockService);
        lenient().when(marketService.enabled()).thenReturn(true);
        lenient().when(marketService.market()).thenReturn(market);
    }

    private Location location(World world, int x, int y, int z) {
        Location l = mock(Location.class);
        lenient().when(l.getWorld()).thenReturn(world);
        lenient().when(l.getBlockX()).thenReturn(x);
        lenient().when(l.getBlockY()).thenReturn(y);
        lenient().when(l.getBlockZ()).thenReturn(z);
        return l;
    }

    private World world(String name) {
        World w = mock(World.class);
        lenient().when(w.getName()).thenReturn(name);
        return w;
    }

    private MarketService.Owner anOwner() {
        return new MarketService.Owner(1, "PERSONAL", null, UUID.randomUUID(), false);
    }

    // ─────────────────────────── onTransaction ───────────────────────────

    @Test
    void onTransaction_disabled_returnsImmediately() {
        when(marketService.enabled()).thenReturn(false);
        sync.onTransaction(mock(Transaction.class));
        verify(marketService, never()).market();
    }

    @Test
    void onTransaction_nullStock_returns() {
        Transaction event = mock(Transaction.class);
        when(event.getStock()).thenReturn(null);
        sync.onTransaction(event);
        verify(marketService, never()).market();
    }

    @Test
    void onTransaction_emptyStock_returns() {
        Transaction event = mock(Transaction.class);
        when(event.getStock()).thenReturn(new ItemStack[0]);
        sync.onTransaction(event);
        verify(marketService, never()).market();
    }

    @Test
    void onTransaction_firstStockNull_returns() {
        Transaction event = mock(Transaction.class);
        when(event.getStock()).thenReturn(new ItemStack[]{null});
        sync.onTransaction(event);
        verify(marketService, never()).market();
    }

    @Test
    void onTransaction_nonAdminBuy_recordsSaleAndUpsertsShop() {
        ItemStack item = mock(ItemStack.class);
        Sign sign = mock(Sign.class);
        Player client = mock(Player.class);
        UUID clientUuid = UUID.randomUUID();
        Account ownerAccount = mock(Account.class);
        UUID ownerUuid = UUID.randomUUID();
        Inventory ownerInv = mock(Inventory.class);
        MarketService.Owner owner = anOwner();
        ChestShopSaleRecord saleRec = mock(ChestShopSaleRecord.class);
        ChestShopShopRecord shopRec = mock(ChestShopShopRecord.class);

        when(client.getUniqueId()).thenReturn(clientUuid);
        when(ownerAccount.getUuid()).thenReturn(ownerUuid);

        Transaction event = mock(Transaction.class);
        when(event.getStock()).thenReturn(new ItemStack[]{item});
        when(event.getSign()).thenReturn(sign);
        when(event.getOwnerAccount()).thenReturn(ownerAccount);
        when(event.getClient()).thenReturn(client);
        when(event.getOwnerInventory()).thenReturn(ownerInv);
        when(event.getExactPrice()).thenReturn(BigDecimal.TEN);
        when(event.getSalesTax()).thenReturn(BigDecimal.ONE);
        when(event.getSettlementTxnId()).thenReturn(42L);
        when(event.getTransactionType()).thenReturn(Transaction.TransactionType.BUY);

        when(signService.isAdminShop(sign)).thenReturn(false);
        when(marketService.totalAmount(any())).thenReturn(7);
        when(marketService.ownerFromUuid(ownerUuid, false)).thenReturn(owner);
        when(marketService.stockOf(item, ownerInv)).thenReturn(15);
        when(marketService.capacityOf(item, ownerInv)).thenReturn(50);
        when(marketService.sale(sign, item, 7, clientUuid, owner, BigDecimal.TEN, BigDecimal.ONE,
                "BUY", 15, 42L)).thenReturn(saleRec);
        when(marketService.shop(sign, item, owner, 15, 50)).thenReturn(shopRec);

        sync.onTransaction(event);

        verify(market).recordSale(saleRec);
        verify(market).upsertShop(shopRec);
    }

    @Test
    void onTransaction_adminShop_nullOwnerAccount_sell_hidesStockAndCapacity() {
        ItemStack item = mock(ItemStack.class);
        Sign sign = mock(Sign.class);
        Player client = mock(Player.class);
        UUID clientUuid = UUID.randomUUID();
        MarketService.Owner owner = new MarketService.Owner(null, null, null, null, true);
        ChestShopSaleRecord saleRec = mock(ChestShopSaleRecord.class);
        ChestShopShopRecord shopRec = mock(ChestShopShopRecord.class);

        when(client.getUniqueId()).thenReturn(clientUuid);

        Transaction event = mock(Transaction.class);
        when(event.getStock()).thenReturn(new ItemStack[]{item});
        when(event.getSign()).thenReturn(sign);
        when(event.getOwnerAccount()).thenReturn(null); // ownerUuid -> null
        when(event.getClient()).thenReturn(client);
        when(event.getExactPrice()).thenReturn(BigDecimal.TEN);
        when(event.getSalesTax()).thenReturn(BigDecimal.ZERO);
        when(event.getSettlementTxnId()).thenReturn(null);
        when(event.getTransactionType()).thenReturn(Transaction.TransactionType.SELL);

        when(signService.isAdminShop(sign)).thenReturn(true);
        when(marketService.totalAmount(any())).thenReturn(3);
        when(marketService.ownerFromUuid(null, true)).thenReturn(owner);
        when(marketService.sale(sign, item, 3, clientUuid, owner, BigDecimal.TEN, BigDecimal.ZERO,
                "SELL", null, null)).thenReturn(saleRec);
        when(marketService.shop(sign, item, owner, null, null)).thenReturn(shopRec);

        sync.onTransaction(event);

        // admin -> stockOf / capacityOf never consulted
        verify(marketService, never()).stockOf(any(), any());
        verify(marketService, never()).capacityOf(any(), any());
        verify(market).recordSale(saleRec);
        verify(market).upsertShop(shopRec);
    }

    @Test
    void onTransaction_throwable_isSwallowed() {
        Transaction event = mock(Transaction.class);
        when(event.getStock()).thenReturn(new ItemStack[]{mock(ItemStack.class)});
        when(event.getSign()).thenReturn(mock(Sign.class));
        // marketService.market() throwing forces the catch arc.
        when(marketService.market()).thenThrow(new RuntimeException("boom"));
        when(event.getOwnerAccount()).thenReturn(null);
        when(event.getClient()).thenReturn(mock(Player.class));
        when(event.getTransactionType()).thenReturn(Transaction.TransactionType.BUY);
        when(signService.isAdminShop(any(Sign.class))).thenReturn(true);

        sync.onTransaction(event); // must not throw
    }

    // ─────────────────────────── onShopCreated ───────────────────────────

    @Test
    void onShopCreated_disabled_returnsImmediately() {
        when(marketService.enabled()).thenReturn(false);
        sync.onShopCreated(mock(CreatedShop.class));
        verify(marketService, never()).market();
    }

    @Test
    void onShopCreated_decodeNull_returns() {
        CreatedShop event = mock(CreatedShop.class);
        String[] lines = {"Owner", "1", "B 10", "Rubbish"};
        when(event.getSign()).thenReturn(mock(Sign.class));
        when(event.getSignLines()).thenReturn(lines);
        when(itemCodes.decode(lines[SignService.ITEM_LINE])).thenReturn(null);

        sync.onShopCreated(event);
        verify(marketService, never()).market();
    }

    @Test
    void onShopCreated_nonAdmin_withContainer_computesStockAndCapacity() {
        Sign sign = mock(Sign.class);
        ItemStack item = mock(ItemStack.class);
        String[] lines = {"Owner", "1", "B 10", "Diamond"};
        Account ownerAccount = mock(Account.class);
        UUID ownerUuid = UUID.randomUUID();
        Container container = mock(Container.class);
        Inventory inv = mock(Inventory.class);
        MarketService.Owner owner = anOwner();
        ChestShopShopRecord shopRec = mock(ChestShopShopRecord.class);

        when(ownerAccount.getUuid()).thenReturn(ownerUuid);
        when(container.getInventory()).thenReturn(inv);

        CreatedShop event = mock(CreatedShop.class);
        when(event.getSign()).thenReturn(sign);
        when(event.getSignLines()).thenReturn(lines);
        when(event.getOwnerAccount()).thenReturn(ownerAccount);
        when(event.getContainer()).thenReturn(container);

        when(itemCodes.decode("Diamond")).thenReturn(item);
        when(signService.isAdminShop(lines)).thenReturn(false);
        when(marketService.ownerFromUuid(ownerUuid, false)).thenReturn(owner);
        when(marketService.stockOf(item, inv)).thenReturn(20);
        when(marketService.capacityOf(item, inv)).thenReturn(30);
        when(marketService.shop(sign, item, owner, 20, 30)).thenReturn(shopRec);

        sync.onShopCreated(event);
        verify(market).upsertShop(shopRec);
    }

    @Test
    void onShopCreated_admin_nullOwnerAccount_nullContainer_hidesStock() {
        Sign sign = mock(Sign.class);
        ItemStack item = mock(ItemStack.class);
        String[] lines = {"Admin Shop", "1", "B 10", "Diamond"};
        MarketService.Owner owner = new MarketService.Owner(null, null, null, null, true);
        ChestShopShopRecord shopRec = mock(ChestShopShopRecord.class);

        CreatedShop event = mock(CreatedShop.class);
        when(event.getSign()).thenReturn(sign);
        when(event.getSignLines()).thenReturn(lines);
        when(event.getOwnerAccount()).thenReturn(null); // ownerUuid -> null
        when(event.getContainer()).thenReturn(null);    // container stays null (admin anyway)

        when(itemCodes.decode("Diamond")).thenReturn(item);
        when(signService.isAdminShop(lines)).thenReturn(true);
        when(marketService.ownerFromUuid(null, true)).thenReturn(owner);
        when(marketService.shop(sign, item, owner, null, null)).thenReturn(shopRec);

        sync.onShopCreated(event);

        verify(marketService, never()).stockOf(any(), any());
        verify(marketService, never()).capacityOf(any(), any());
        verify(market).upsertShop(shopRec);
    }

    @Test
    void onShopCreated_nonAdmin_nullContainer_leavesStockNull() {
        // admin=false but no container -> the (!admin && container != null) is false, stock/cap null.
        Sign sign = mock(Sign.class);
        ItemStack item = mock(ItemStack.class);
        String[] lines = {"Owner", "1", "B 10", "Diamond"};
        MarketService.Owner owner = anOwner();
        ChestShopShopRecord shopRec = mock(ChestShopShopRecord.class);

        CreatedShop event = mock(CreatedShop.class);
        when(event.getSign()).thenReturn(sign);
        when(event.getSignLines()).thenReturn(lines);
        when(event.getOwnerAccount()).thenReturn(null);
        when(event.getContainer()).thenReturn(null);

        when(itemCodes.decode("Diamond")).thenReturn(item);
        when(signService.isAdminShop(lines)).thenReturn(false);
        when(marketService.ownerFromUuid(null, false)).thenReturn(owner);
        when(marketService.shop(sign, item, owner, null, null)).thenReturn(shopRec);

        sync.onShopCreated(event);

        verify(marketService, never()).stockOf(any(), any());
        verify(marketService, never()).capacityOf(any(), any());
        verify(market).upsertShop(shopRec);
    }

    @Test
    void onShopCreated_throwable_isSwallowed() {
        CreatedShop event = mock(CreatedShop.class);
        // getSignLines() throwing forces the catch arc.
        when(event.getSign()).thenReturn(mock(Sign.class));
        when(event.getSignLines()).thenThrow(new RuntimeException("boom"));

        sync.onShopCreated(event); // must not throw
    }

    // ─────────────────────────── onShopDestroyed ───────────────────────────

    @Test
    void onShopDestroyed_disabled_returnsImmediately() {
        when(marketService.enabled()).thenReturn(false);
        sync.onShopDestroyed(mock(DestroyedShop.class));
        verify(marketService, never()).market();
    }

    @Test
    void onShopDestroyed_namedWorld_deactivatesWithWorldName() {
        Sign sign = mock(Sign.class);
        World world = world("world");
        Location l = location(world, 1, 2, 3);
        when(sign.getLocation()).thenReturn(l);

        DestroyedShop event = mock(DestroyedShop.class);
        when(event.getSign()).thenReturn(sign);

        sync.onShopDestroyed(event);
        verify(market).deactivateShop("world", 1, 2, 3);
    }

    @Test
    void onShopDestroyed_nullWorld_deactivatesWithNullWorldName() {
        Sign sign = mock(Sign.class);
        Location l = location(null, 4, 5, 6);
        when(sign.getLocation()).thenReturn(l);

        DestroyedShop event = mock(DestroyedShop.class);
        when(event.getSign()).thenReturn(sign);

        sync.onShopDestroyed(event);
        verify(market).deactivateShop(null, 4, 5, 6);
    }

    @Test
    void onShopDestroyed_throwable_isSwallowed() {
        DestroyedShop event = mock(DestroyedShop.class);
        // getSign() throwing forces the catch arc.
        when(event.getSign()).thenThrow(new RuntimeException("boom"));

        sync.onShopDestroyed(event); // must not throw
    }

    // ─────────────────────────── onInventoryClose ───────────────────────────

    @Test
    void onInventoryClose_disabled_returnsImmediately() {
        when(marketService.enabled()).thenReturn(false);
        sync.onInventoryClose(mock(InventoryHolder.class), mock(Inventory.class));
        verify(marketService, never()).market();
    }

    @Test
    void onInventoryClose_nullHolder_returns() {
        sync.onInventoryClose(null, mock(Inventory.class));
        verify(marketService, never()).market();
        verifyNoInteractions(shopBlockService);
    }

    @Test
    void onInventoryClose_noSigns_returns() {
        InventoryHolder holder = mock(InventoryHolder.class);
        when(shopBlockService.findConnectedShopSigns(holder)).thenReturn(List.of());
        sync.onInventoryClose(holder, mock(Inventory.class));
        verify(marketService, never()).market();
    }

    @Test
    void onInventoryClose_adminSign_isSkipped() {
        InventoryHolder holder = mock(InventoryHolder.class);
        Inventory inv = mock(Inventory.class);
        Sign sign = mock(Sign.class);
        when(shopBlockService.findConnectedShopSigns(holder)).thenReturn(List.of(sign));
        when(signService.isAdminShop(sign)).thenReturn(true);

        try (MockedStatic<SignService> statics = mockStatic(SignService.class)) {
            sync.onInventoryClose(holder, inv);
            statics.verify(() -> SignService.getItem(any(Sign.class)), never());
        }
        verify(market, never()).updateShopStock(any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void onInventoryClose_nullItemName_isSkipped() {
        InventoryHolder holder = mock(InventoryHolder.class);
        Inventory inv = mock(Inventory.class);
        Sign sign = mock(Sign.class);
        when(shopBlockService.findConnectedShopSigns(holder)).thenReturn(List.of(sign));
        when(signService.isAdminShop(sign)).thenReturn(false);

        try (MockedStatic<SignService> statics = mockStatic(SignService.class)) {
            statics.when(() -> SignService.getItem(sign)).thenReturn(null); // itemName null -> item null
            sync.onInventoryClose(holder, inv);
        }
        verify(itemCodes, never()).decode(any());
        verify(market, never()).updateShopStock(any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void onInventoryClose_decodeNull_isSkipped() {
        InventoryHolder holder = mock(InventoryHolder.class);
        Inventory inv = mock(Inventory.class);
        Sign sign = mock(Sign.class);
        when(shopBlockService.findConnectedShopSigns(holder)).thenReturn(List.of(sign));
        when(signService.isAdminShop(sign)).thenReturn(false);

        try (MockedStatic<SignService> statics = mockStatic(SignService.class)) {
            statics.when(() -> SignService.getItem(sign)).thenReturn("Diamond");
            when(itemCodes.decode("Diamond")).thenReturn(null); // item null -> skip
            sync.onInventoryClose(holder, inv);
        }
        verify(market, never()).updateShopStock(any(), anyInt(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void onInventoryClose_namedWorld_updatesShopStock() {
        InventoryHolder holder = mock(InventoryHolder.class);
        Inventory inv = mock(Inventory.class);
        Sign sign = mock(Sign.class);
        ItemStack item = mock(ItemStack.class);
        Location l = location(world("world"), 7, 8, 9);
        when(sign.getLocation()).thenReturn(l);
        when(shopBlockService.findConnectedShopSigns(holder)).thenReturn(List.of(sign));
        when(signService.isAdminShop(sign)).thenReturn(false);
        when(itemCodes.decode("Diamond")).thenReturn(item);
        when(marketService.stockOf(item, inv)).thenReturn(11);
        when(marketService.capacityOf(item, inv)).thenReturn(22);

        try (MockedStatic<SignService> statics = mockStatic(SignService.class)) {
            statics.when(() -> SignService.getItem(sign)).thenReturn("Diamond");
            sync.onInventoryClose(holder, inv);
        }
        verify(market).updateShopStock("world", 7, 8, 9, 11, 22);
    }

    @Test
    void onInventoryClose_nullWorld_updatesWithNullWorldName() {
        InventoryHolder holder = mock(InventoryHolder.class);
        Inventory inv = mock(Inventory.class);
        Sign sign = mock(Sign.class);
        ItemStack item = mock(ItemStack.class);
        Location l = location(null, 1, 2, 3);
        when(sign.getLocation()).thenReturn(l);
        when(shopBlockService.findConnectedShopSigns(holder)).thenReturn(List.of(sign));
        when(signService.isAdminShop(sign)).thenReturn(false);
        when(itemCodes.decode("Diamond")).thenReturn(item);
        when(marketService.stockOf(item, inv)).thenReturn(0);
        when(marketService.capacityOf(item, inv)).thenReturn(5);

        try (MockedStatic<SignService> statics = mockStatic(SignService.class)) {
            statics.when(() -> SignService.getItem(sign)).thenReturn("Diamond");
            sync.onInventoryClose(holder, inv);
        }
        verify(market).updateShopStock(null, 1, 2, 3, 0, 5);
    }

    @Test
    void onInventoryClose_throwable_isSwallowed() {
        InventoryHolder holder = mock(InventoryHolder.class);
        // findConnectedShopSigns throwing forces the catch arc.
        when(shopBlockService.findConnectedShopSigns(holder)).thenThrow(new RuntimeException("boom"));

        sync.onInventoryClose(holder, mock(Inventory.class)); // must not throw
    }
}
