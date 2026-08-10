package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.model.ShopCreation;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.MaterialService;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.utils.InventoryUtil;
import io.paradaux.chestshop.utils.QuantityUtil;
import io.paradaux.chestshop.utils.SignText;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Formatter;
import java.util.IllegalFormatException;
import java.util.List;

import static io.paradaux.chestshop.services.SignService.QUANTITY_LINE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link StockCounterServiceImpl}: the {@code Q n : C m} stock-counter maths that
 * keep a shop sign's quantity line in sync with its container. Every collaborator is a Mockito mock;
 * the static {@link SignService}/{@link SignText}/{@link QuantityUtil}/{@link InventoryUtil} helpers
 * are stubbed with {@code mockStatic} per test. {@code isAdminShop}/{@code getShopBlock} are the only
 * <em>instance</em> {@link SignService} calls, so a separate {@link SignService} mock backs those.
 *
 * <p>Note on the catch arcs: {@code onPreShopCreation} catches {@link IllegalArgumentException}, but
 * {@code updateCounterOnQuantityLine}/{@code removeCounterFromQuantityLine} catch the narrower
 * {@link IllegalFormatException}. The tests throw the exact type each catch expects.
 */
class StockCounterServiceImplTest {

    private ItemService items;
    private ChestShopConfiguration config;
    private SignService signService;
    private ShopBlockService shopBlockService;
    private InventoryService inventoryService;
    private MaterialService materialService;

    private StockCounterServiceImpl service;

    @BeforeEach
    void setUp() {
        items = mock(ItemService.class);
        config = mock(ChestShopConfiguration.class);
        signService = mock(SignService.class);
        shopBlockService = mock(ShopBlockService.class);
        inventoryService = mock(InventoryService.class);
        materialService = mock(MaterialService.class);

        service = new StockCounterServiceImpl(items, config, signService, shopBlockService,
                inventoryService, materialService);
    }

    // A genuine IllegalFormatException instance for the narrow catch arcs.
    private static IllegalFormatException illegalFormat() {
        try {
            new Formatter().format("%d", "not-a-number"); // throws IllegalFormatConversionException
            throw new AssertionError("expected an IllegalFormatException");
        } catch (IllegalFormatException e) {
            return e;
        }
    }

    private ShopCreation shopCreation(String[] lines, Sign sign) {
        return new ShopCreation(mock(org.bukkit.entity.Player.class), sign, lines);
    }

    // ═══════════════════════════ onPreShopCreation ═══════════════════════════

    @Test
    void onPreShopCreation_returnsWhenQuantityInvalid() {
        String[] lines = {"Notch", "bad", "B 5", "Diamond"};
        Sign sign = mock(Sign.class);
        ShopCreation event = shopCreation(lines, sign);

        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getQuantity(any(String[].class)))
                    .thenThrow(new IllegalArgumentException("nope"));

            service.onPreShopCreation(event);
        }
        // Untouched line 1.
        org.assertj.core.api.Assertions.assertThat(event.getSignLine(QUANTITY_LINE)).isEqualTo("bad");
    }

    @Test
    void onPreShopCreation_normalisesCounterLine_thenReturnsWhenStockCounterDisabled() {
        String[] lines = {"Notch", "Q 64 : C 3", "B 5", "Diamond"};
        Sign sign = mock(Sign.class);
        ShopCreation event = shopCreation(lines, sign);
        when(config.isUseStockCounter()).thenReturn(false);

        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<QuantityUtil> qu = mockStatic(QuantityUtil.class)) {
            ss.when(() -> SignService.getQuantity(any(String[].class))).thenReturn(64);
            ss.when(() -> SignService.getQuantityLine(any(String[].class))).thenReturn("Q 64 : C 3");
            qu.when(() -> QuantityUtil.quantityLineContainsCounter("Q 64 : C 3")).thenReturn(true);

            service.onPreShopCreation(event);
        }
        // The counter line was normalised to the bare quantity.
        org.assertj.core.api.Assertions.assertThat(event.getSignLine(QUANTITY_LINE)).isEqualTo("64");
    }

    @Test
    void onPreShopCreation_returnsForUnlimitedAdminShop() {
        String[] lines = {"Admin Shop", "64", "B 5", "Diamond"};
        Sign sign = mock(Sign.class);
        ShopCreation event = shopCreation(lines, sign);
        when(config.isUseStockCounter()).thenReturn(true);
        when(config.isForceUnlimitedAdminShop()).thenReturn(true);
        when(signService.isAdminShop(any(String[].class))).thenReturn(true);

        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<QuantityUtil> qu = mockStatic(QuantityUtil.class)) {
            ss.when(() -> SignService.getQuantity(any(String[].class))).thenReturn(64);
            ss.when(() -> SignService.getQuantityLine(any(String[].class))).thenReturn("64");
            qu.when(() -> QuantityUtil.quantityLineContainsCounter("64")).thenReturn(false);

            service.onPreShopCreation(event);
        }
        verify(shopBlockService, never()).findConnectedContainer(any(Sign.class));
    }

    @Test
    void onPreShopCreation_warnsAndReturnsWhenMaxShopAmountTooLarge() {
        String[] lines = {"Notch", "64", "B 5", "Diamond"};
        Sign sign = mock(Sign.class);
        ShopCreation event = shopCreation(lines, sign);
        when(config.isUseStockCounter()).thenReturn(true);
        when(config.isForceUnlimitedAdminShop()).thenReturn(false);
        when(config.getMaxShopAmount()).thenReturn(100000);

        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<QuantityUtil> qu = mockStatic(QuantityUtil.class)) {
            ss.when(() -> SignService.getQuantity(any(String[].class))).thenReturn(64);
            ss.when(() -> SignService.getQuantityLine(any(String[].class))).thenReturn("64");
            qu.when(() -> QuantityUtil.quantityLineContainsCounter("64")).thenReturn(false);

            service.onPreShopCreation(event);
        }
        verify(shopBlockService, never()).findConnectedContainer(any(Sign.class));
    }

    @Test
    void onPreShopCreation_seedsCounter_whenItemAndContainerPresent() {
        String[] lines = {"Notch", "64", "B 5", "Diamond"};
        Sign sign = mock(Sign.class);
        ShopCreation event = shopCreation(lines, sign);
        when(config.isUseStockCounter()).thenReturn(true);
        // forceUnlimitedAdminShop on but this is NOT an admin shop → the && right operand is false
        // and the guard falls through (covers the isAdminShop-false arc of line 76).
        when(config.isForceUnlimitedAdminShop()).thenReturn(true);
        when(signService.isAdminShop(any(String[].class))).thenReturn(false);
        when(config.getMaxShopAmount()).thenReturn(3840);

        ItemStack traded = mock(ItemStack.class);
        when(items.parse("Diamond")).thenReturn(traded);
        Container container = mock(Container.class, RETURNS_DEEP_STUBS);
        Inventory inv = mock(Inventory.class);
        when(container.getInventory()).thenReturn(inv);
        when(shopBlockService.findConnectedContainer(sign)).thenReturn(container);
        when(inventoryService.getAmount(traded, inv)).thenReturn(12);

        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<QuantityUtil> qu = mockStatic(QuantityUtil.class)) {
            ss.when(() -> SignService.getQuantity(any(String[].class))).thenReturn(64);
            ss.when(() -> SignService.getQuantityLine(any(String[].class))).thenReturn("64");
            ss.when(() -> SignService.getItem(any(String[].class))).thenReturn("Diamond");
            qu.when(() -> QuantityUtil.quantityLineContainsCounter("64")).thenReturn(false);

            service.onPreShopCreation(event);
        }
        org.assertj.core.api.Assertions.assertThat(event.getSignLine(QUANTITY_LINE)).isEqualTo("Q 64 : C 12");
    }

    @Test
    void onPreShopCreation_skipsSeed_whenItemUnparseable() {
        String[] lines = {"Notch", "64", "B 5", "Rubbish"};
        Sign sign = mock(Sign.class);
        ShopCreation event = shopCreation(lines, sign);
        when(config.isUseStockCounter()).thenReturn(true);
        when(config.isForceUnlimitedAdminShop()).thenReturn(false);
        when(config.getMaxShopAmount()).thenReturn(3840);
        when(items.parse("Rubbish")).thenReturn(null);

        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<QuantityUtil> qu = mockStatic(QuantityUtil.class)) {
            ss.when(() -> SignService.getQuantity(any(String[].class))).thenReturn(64);
            ss.when(() -> SignService.getQuantityLine(any(String[].class))).thenReturn("64");
            ss.when(() -> SignService.getItem(any(String[].class))).thenReturn("Rubbish");
            qu.when(() -> QuantityUtil.quantityLineContainsCounter("64")).thenReturn(false);

            service.onPreShopCreation(event);
        }
        verify(shopBlockService, never()).findConnectedContainer(any(Sign.class));
        org.assertj.core.api.Assertions.assertThat(event.getSignLine(QUANTITY_LINE)).isEqualTo("64");
    }

    @Test
    void onPreShopCreation_skipsSeed_whenNoContainer() {
        String[] lines = {"Notch", "64", "B 5", "Diamond"};
        Sign sign = mock(Sign.class);
        ShopCreation event = shopCreation(lines, sign);
        when(config.isUseStockCounter()).thenReturn(true);
        when(config.isForceUnlimitedAdminShop()).thenReturn(false);
        when(config.getMaxShopAmount()).thenReturn(3840);
        when(items.parse("Diamond")).thenReturn(mock(ItemStack.class));
        when(shopBlockService.findConnectedContainer(sign)).thenReturn(null);

        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<QuantityUtil> qu = mockStatic(QuantityUtil.class)) {
            ss.when(() -> SignService.getQuantity(any(String[].class))).thenReturn(64);
            ss.when(() -> SignService.getQuantityLine(any(String[].class))).thenReturn("64");
            ss.when(() -> SignService.getItem(any(String[].class))).thenReturn("Diamond");
            qu.when(() -> QuantityUtil.quantityLineContainsCounter("64")).thenReturn(false);

            service.onPreShopCreation(event);
        }
        org.assertj.core.api.Assertions.assertThat(event.getSignLine(QUANTITY_LINE)).isEqualTo("64");
    }

    // ═══════════════════════════ onTransaction ═══════════════════════════

    @Test
    void onTransaction_stockCounterDisabled_removesExistingCounter() {
        Sign sign = mock(Sign.class);
        Transaction event = mock(Transaction.class);
        when(event.getSign()).thenReturn(sign);
        when(config.isUseStockCounter()).thenReturn(false);

        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<QuantityUtil> qu = mockStatic(QuantityUtil.class);
             MockedStatic<SignText> st = mockStatic(SignText.class)) {
            ss.when(() -> SignService.getQuantityLine(sign)).thenReturn("Q 64 : C 3");
            ss.when(() -> SignService.getQuantity(sign)).thenReturn(64);
            qu.when(() -> QuantityUtil.quantityLineContainsCounter("Q 64 : C 3")).thenReturn(true);

            service.onTransaction(event);

            // removeCounterFromQuantityLine rewrote the bare quantity + updated the sign.
            st.verify(() -> SignText.setLine(sign, QUANTITY_LINE, "64"));
        }
        verify(sign).update(true);
    }

    @Test
    void onTransaction_stockCounterDisabled_noCounter_doesNothing() {
        Sign sign = mock(Sign.class);
        Transaction event = mock(Transaction.class);
        when(event.getSign()).thenReturn(sign);
        when(config.isUseStockCounter()).thenReturn(false);

        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<QuantityUtil> qu = mockStatic(QuantityUtil.class);
             MockedStatic<SignText> st = mockStatic(SignText.class)) {
            ss.when(() -> SignService.getQuantityLine(sign)).thenReturn("64");
            qu.when(() -> QuantityUtil.quantityLineContainsCounter("64")).thenReturn(false);

            service.onTransaction(event);

            st.verifyNoInteractions();
        }
        verify(sign, never()).update(true);
    }

    @Test
    void onTransaction_maxShopAmountTooLarge_removesCounter() {
        Sign sign = mock(Sign.class);
        Transaction event = mock(Transaction.class);
        when(event.getSign()).thenReturn(sign);
        when(config.isUseStockCounter()).thenReturn(true);
        when(config.getMaxShopAmount()).thenReturn(100000);

        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<QuantityUtil> qu = mockStatic(QuantityUtil.class);
             MockedStatic<SignText> st = mockStatic(SignText.class)) {
            ss.when(() -> SignService.getQuantityLine(sign)).thenReturn("Q 64 : C 3");
            ss.when(() -> SignService.getQuantity(sign)).thenReturn(64);
            qu.when(() -> QuantityUtil.quantityLineContainsCounter("Q 64 : C 3")).thenReturn(true);

            service.onTransaction(event);

            st.verify(() -> SignText.setLine(sign, QUANTITY_LINE, "64"));
        }
        verify(sign).update(true);
    }

    @Test
    void onTransaction_maxShopAmountTooLarge_noCounter_returns() {
        Sign sign = mock(Sign.class);
        Transaction event = mock(Transaction.class);
        when(event.getSign()).thenReturn(sign);
        when(config.isUseStockCounter()).thenReturn(true);
        when(config.getMaxShopAmount()).thenReturn(100000);

        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<QuantityUtil> qu = mockStatic(QuantityUtil.class);
             MockedStatic<SignText> st = mockStatic(SignText.class)) {
            ss.when(() -> SignService.getQuantityLine(sign)).thenReturn("64");
            qu.when(() -> QuantityUtil.quantityLineContainsCounter("64")).thenReturn(false);

            service.onTransaction(event);

            st.verifyNoInteractions();
        }
        verify(shopBlockService, never()).findConnectedShopSigns(any(InventoryHolder.class));
    }

    @Test
    void onTransaction_returnsForUnlimitedAdminShop() {
        Sign sign = mock(Sign.class);
        Transaction event = mock(Transaction.class);
        when(event.getSign()).thenReturn(sign);
        when(config.isUseStockCounter()).thenReturn(true);
        when(config.getMaxShopAmount()).thenReturn(3840);
        when(config.isForceUnlimitedAdminShop()).thenReturn(true);
        when(signService.isAdminShop(sign)).thenReturn(true);

        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<QuantityUtil> qu = mockStatic(QuantityUtil.class)) {
            ss.when(() -> SignService.getQuantityLine(sign)).thenReturn("64");
            qu.when(() -> QuantityUtil.quantityLineContainsCounter("64")).thenReturn(false);

            service.onTransaction(event);
        }
        verify(shopBlockService, never()).findConnectedShopSigns(any(InventoryHolder.class));
    }

    @Test
    void onTransaction_forceUnlimitedDisabled_stillUpdatesConnectedSigns() {
        // forceUnlimitedAdminShop off → the && short-circuits on its left operand (line 113 left-false
        // arc); the loop runs regardless of admin-shop status.
        Sign sign = mock(Sign.class);
        Sign connected = mock(Sign.class);
        Transaction event = mock(Transaction.class);
        Inventory ownerInv = mock(Inventory.class);
        InventoryHolder holder = mock(InventoryHolder.class);
        when(event.getSign()).thenReturn(sign);
        when(event.getOwnerInventory()).thenReturn(ownerInv);
        when(config.isUseStockCounter()).thenReturn(true);
        when(config.getMaxShopAmount()).thenReturn(3840);
        when(config.isForceUnlimitedAdminShop()).thenReturn(false);
        when(shopBlockService.findConnectedShopSigns(holder)).thenReturn(List.of(connected));

        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<QuantityUtil> qu = mockStatic(QuantityUtil.class);
             MockedStatic<InventoryUtil> iu = mockStatic(InventoryUtil.class)) {
            ss.when(() -> SignService.getQuantityLine(sign)).thenReturn("64");
            ss.when(() -> SignService.getItem(connected)).thenReturn("Rubbish");
            qu.when(() -> QuantityUtil.quantityLineContainsCounter("64")).thenReturn(false);
            iu.when(() -> InventoryUtil.getHolder(ownerInv, false)).thenReturn(holder);
            when(items.parse("Rubbish")).thenReturn(null);

            service.onTransaction(event);
        }
        verify(shopBlockService).findConnectedShopSigns(holder);
    }

    @Test
    void onTransaction_updatesEveryConnectedShopSign() {
        Sign sign = mock(Sign.class);
        Sign connected = mock(Sign.class);
        Transaction event = mock(Transaction.class);
        Inventory ownerInv = mock(Inventory.class);
        InventoryHolder holder = mock(InventoryHolder.class);
        when(event.getSign()).thenReturn(sign);
        when(event.getOwnerInventory()).thenReturn(ownerInv);
        when(config.isUseStockCounter()).thenReturn(true);
        when(config.getMaxShopAmount()).thenReturn(3840);
        // forceUnlimitedAdminShop on but this is NOT an admin shop → the && right operand is false
        // and the guard falls through (covers the isAdminShop-false arc of line 113).
        when(config.isForceUnlimitedAdminShop()).thenReturn(true);
        when(signService.isAdminShop(sign)).thenReturn(false);
        when(shopBlockService.findConnectedShopSigns(holder)).thenReturn(List.of(connected));

        // The connected sign trades an unparseable item → updateCounterOnQuantityLine returns early,
        // which is enough to prove the loop body ran (and keeps this test focused on onTransaction).
        when(ownerInv.getHolder(false)).thenReturn(holder);

        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<QuantityUtil> qu = mockStatic(QuantityUtil.class);
             MockedStatic<InventoryUtil> iu = mockStatic(InventoryUtil.class)) {
            ss.when(() -> SignService.getQuantityLine(sign)).thenReturn("64");
            ss.when(() -> SignService.getItem(connected)).thenReturn("Rubbish");
            qu.when(() -> QuantityUtil.quantityLineContainsCounter("64")).thenReturn(false);
            iu.when(() -> InventoryUtil.getHolder(ownerInv, false)).thenReturn(holder);
            when(items.parse("Rubbish")).thenReturn(null); // item null → update loop body returns

            service.onTransaction(event);
        }
        verify(shopBlockService).findConnectedShopSigns(holder);
    }

    // ═══════════════════════════ updateCounterOnQuantityLine ═══════════════════════════

    @Test
    void updateCounter_returnsWhenItemUnparseable() {
        Sign sign = mock(Sign.class);
        Inventory inv = mock(Inventory.class);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getItem(sign)).thenReturn("Rubbish");
            when(items.parse("Rubbish")).thenReturn(null);

            service.updateCounterOnQuantityLine(sign, inv);
        }
        verify(inventoryService, never()).getAmount(any(), any());
    }

    @Test
    void updateCounter_returnsWhenQuantityFormatInvalid() {
        Sign sign = mock(Sign.class);
        Inventory inv = mock(Inventory.class);
        ItemStack traded = mock(ItemStack.class);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getItem(sign)).thenReturn("Diamond");
            when(items.parse("Diamond")).thenReturn(traded);
            ss.when(() -> SignService.getQuantity(sign)).thenThrow(illegalFormat());

            service.updateCounterOnQuantityLine(sign, inv);
        }
        verify(inventoryService, never()).getAmount(any(), any());
    }

    @Test
    void updateCounter_countsExtraMatchingItems_andRewritesLine() {
        Sign sign = mock(Sign.class);
        Inventory inv = mock(Inventory.class);
        ItemStack traded = mock(ItemStack.class);
        ItemStack match = mock(ItemStack.class);
        ItemStack other = mock(ItemStack.class);
        when(match.getAmount()).thenReturn(5);

        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<SignText> st = mockStatic(SignText.class)) {
            ss.when(() -> SignService.getItem(sign)).thenReturn("Diamond");
            when(items.parse("Diamond")).thenReturn(traded);
            ss.when(() -> SignService.getQuantity(sign)).thenReturn(64);
            when(inventoryService.getAmount(traded, inv)).thenReturn(10);
            when(materialService.equals(match, traded)).thenReturn(true);
            when(materialService.equals(other, traded)).thenReturn(false);
            st.when(() -> SignText.getLine(sign, QUANTITY_LINE)).thenReturn("Q 64 : C 3"); // differs

            service.updateCounterOnQuantityLine(sign, inv, match, other);

            // 10 (chest) + 5 (matching extra) = 15; non-matching 'other' skipped.
            st.verify(() -> SignText.setLine(sign, QUANTITY_LINE, "Q 64 : C 15"));
        }
        verify(sign).update(true);
    }

    @Test
    void updateCounter_returnsEarlyWhenCounterLineUnchanged() {
        Sign sign = mock(Sign.class);
        Inventory inv = mock(Inventory.class);
        ItemStack traded = mock(ItemStack.class);

        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<SignText> st = mockStatic(SignText.class)) {
            ss.when(() -> SignService.getItem(sign)).thenReturn("Diamond");
            when(items.parse("Diamond")).thenReturn(traded);
            ss.when(() -> SignService.getQuantity(sign)).thenReturn(64);
            when(inventoryService.getAmount(traded, inv)).thenReturn(10);
            st.when(() -> SignText.getLine(sign, QUANTITY_LINE)).thenReturn("Q 64 : C 10"); // same

            service.updateCounterOnQuantityLine(sign, inv);

            st.verify(() -> SignText.setLine(any(Sign.class), org.mockito.ArgumentMatchers.anyInt(), anyString()), never());
        }
        verify(sign, never()).update(true);
    }

    // ═══════════════════════════ updateCounterOnItemMoveEvent ═══════════════════════════

    @Test
    void updateCounterOnItemMoveEvent_resolvesSignAndDelegates() {
        ItemStack toAdd = mock(ItemStack.class);
        InventoryHolder destination = mock(InventoryHolder.class);
        Block shopBlock = mock(Block.class);
        Sign connected = mock(Sign.class);
        Inventory inv = mock(Inventory.class);
        when(signService.getShopBlock(destination)).thenReturn(shopBlock);
        when(shopBlockService.getConnectedSign(shopBlock)).thenReturn(connected);
        when(destination.getInventory()).thenReturn(inv);

        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            // The delegated updateCounterOnQuantityLine bails at the item==null guard.
            ss.when(() -> SignService.getItem(connected)).thenReturn("Rubbish");
            when(items.parse("Rubbish")).thenReturn(null);

            service.updateCounterOnItemMoveEvent(toAdd, destination);
        }
        verify(signService).getShopBlock(destination);
        verify(shopBlockService).getConnectedSign(shopBlock);
    }

    // ═══════════════════════════ removeCounterFromQuantityLine ═══════════════════════════

    @Test
    void removeCounter_returnsWhenQuantityFormatInvalid() {
        Sign sign = mock(Sign.class);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<SignText> st = mockStatic(SignText.class)) {
            ss.when(() -> SignService.getQuantity(sign)).thenThrow(illegalFormat());

            service.removeCounterFromQuantityLine(sign);

            st.verifyNoInteractions();
        }
        verify(sign, never()).update(true);
    }

    @Test
    void removeCounter_rewritesBareQuantity() {
        Sign sign = mock(Sign.class);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<SignText> st = mockStatic(SignText.class)) {
            ss.when(() -> SignService.getQuantity(sign)).thenReturn(64);

            service.removeCounterFromQuantityLine(sign);

            st.verify(() -> SignText.setLine(sign, QUANTITY_LINE, "64"));
        }
        verify(sign).update(true);
    }

    // ═══════════════════════════ getQuantityLineWithCounter ═══════════════════════════

    @Test
    void getQuantityLineWithCounter_formatsQuantityAndStock() {
        Inventory inv = mock(Inventory.class);
        ItemStack traded = mock(ItemStack.class);
        when(inventoryService.getAmount(traded, inv)).thenReturn(7);

        String line = service.getQuantityLineWithCounter(64, traded, inv);

        org.assertj.core.api.Assertions.assertThat(line).isEqualTo("Q 64 : C 7");
    }

    // ═══════════════════════════ determineItemTradedByShop ═══════════════════════════

    @Test
    void determineItemTradedByShop_fromSign_parsesSignItem() {
        Sign sign = mock(Sign.class);
        ItemStack traded = mock(ItemStack.class);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getItem(sign)).thenReturn("Diamond");
            when(items.parse("Diamond")).thenReturn(traded);

            org.assertj.core.api.Assertions.assertThat(service.determineItemTradedByShop(sign)).isSameAs(traded);
        }
    }

    @Test
    void determineItemTradedByShop_fromMaterial_delegatesToItemService() {
        ItemStack traded = mock(ItemStack.class);
        when(items.parse("Diamond")).thenReturn(traded);

        org.assertj.core.api.Assertions.assertThat(service.determineItemTradedByShop("Diamond")).isSameAs(traded);
    }
}
