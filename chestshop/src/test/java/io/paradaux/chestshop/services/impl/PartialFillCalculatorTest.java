package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.services.PartialFillCalculator;

import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.MaterialService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;
import static io.paradaux.chestshop.model.Transaction.TransactionType.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drives {@link PartialFillCalculator#adjustBuy}/{@link PartialFillCalculator#adjustSell}
 * through every partial-fill outcome (unaffordable, out-of-stock, no-space, rounded-to-zero,
 * deposit-refused) and the scaling helpers ({@code getItems}/{@code getCountedItemStack}/
 * {@code getItemsThatFit}). Bukkit {@link ItemStack}s can't be instantiated headless, so
 * stateful mocks stand in — {@code getAmount}/{@code setAmount}/{@code clone} are backed by a
 * mutable holder, matching the real stacking maths the calculator performs. The economy is a
 * {@link FakeEconomy} (see that class for why it isn't a Mockito mock).
 */
class PartialFillCalculatorTest {

    private FakeEconomy economy;
    private InventoryService inventory;
    private MaterialService materials;
    private ChestShopConfiguration config;
    private PartialFillCalculator calc;

    private Player client;
    private UUID clientId;
    private Account ownerAccount;
    private UUID ownerId;
    private Inventory ownerInv;
    private Inventory clientInv;

    @BeforeEach
    void setUp() {
        economy = new FakeEconomy();
        inventory = mock(InventoryService.class);
        materials = mock(MaterialService.class);
        config = mock(ChestShopConfiguration.class);
        when(config.getPricePrecision()).thenReturn(2);
        calc = new PartialFillCalculatorImpl(economy, inventory, materials, config);

        clientId = UUID.randomUUID();
        client = mock(Player.class);
        lenient().when(client.getUniqueId()).thenReturn(clientId);

        ownerId = UUID.randomUUID();
        ownerAccount = new Account("Owner", ownerId);
        ownerInv = mock(Inventory.class);
        clientInv = mock(Inventory.class);

        // Two items are "equal" when their materials match — the calculator's stacking predicate.
        lenient().when(materials.equals(any(), any())).thenAnswer(i -> {
            ItemStack a = i.getArgument(0);
            ItemStack b = i.getArgument(1);
            return a != null && b != null && a.getType() == b.getType();
        });
        // getItemsStacked echoes the requested stack (no new mocks created inside the answer —
        // creating/stubbing a Mockito mock mid-answer corrupts the stubbing state).
        lenient().when(inventory.getItemsStacked(any(ItemStack.class)))
                .thenAnswer(i -> new ItemStack[]{i.getArgument(0)});
    }

    // ── stateful ItemStack fake ───────────────────────────────────────────────
    // Built with a single default Answer (not per-method when() stubs) so it can be created
    // safely even inside another mock's stubbing argument — nested when()/doAnswer() calls
    // would otherwise trip Mockito's UnfinishedStubbingException.
    private ItemStack item(Material type, int amount) {
        int[] amt = {amount};
        Answer<Object> behaviour = inv -> switch (inv.getMethod().getName()) {
            case "getType" -> type;
            case "getAmount" -> amt[0];
            case "setAmount" -> { amt[0] = inv.getArgument(0); yield null; }
            case "clone" -> item(type, amt[0]);
            case "toString" -> "Item(" + type + "x" + amt[0] + ")";
            case "equals" -> inv.getMock() == inv.getArgument(0);
            case "hashCode" -> System.identityHashCode(inv.getMock());
            default -> Mockito.RETURNS_DEFAULTS.answer(inv);
        };
        return mock(ItemStack.class, behaviour);
    }

    private PendingTransaction ctx(ItemStack[] stock, BigDecimal price, boolean unlimited,
                                   io.paradaux.chestshop.model.Transaction.TransactionType type) {
        return new PendingTransaction(ownerInv, clientInv, stock, price, client, ownerAccount, null, type, unlimited);
    }

    // ═══════════════════ early-exit guards ═══════════════════

    @Test
    void adjustBuy_returnsImmediately_whenAlreadyCancelled() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"), false, BUY);
        ctx.setCancelled(TransactionOutcome.INVALID_SHOP);
        calc.adjustBuy(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.INVALID_SHOP);
    }

    @Test
    void adjustBuy_returnsImmediately_forSellTransaction() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"), false, SELL);
        calc.adjustBuy(ctx);
        assertThat(ctx.isCancelled()).isFalse();
    }

    @Test
    void adjustBuy_returnsImmediately_whenNoItems() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 0)}, new BigDecimal("5"), false, BUY);
        calc.adjustBuy(ctx);
        assertThat(ctx.isCancelled()).isFalse();
    }

    @Test
    void adjustSell_returnsImmediately_whenAlreadyCancelled() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"), false, SELL);
        ctx.setCancelled(TransactionOutcome.INVALID_SHOP);
        calc.adjustSell(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.INVALID_SHOP);
    }

    @Test
    void adjustSell_returnsImmediately_forBuyTransaction() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"), false, BUY);
        calc.adjustSell(ctx);
        assertThat(ctx.isCancelled()).isFalse();
    }

    @Test
    void adjustSell_returnsImmediately_whenNoItems() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 0)}, new BigDecimal("5"), false, SELL);
        calc.adjustSell(ctx);
        assertThat(ctx.isCancelled()).isFalse();
    }

    // ═══════════════════ adjustBuy — affordability ═══════════════════

    @Test
    void adjustBuy_fullyAffordableAndInStock_completesUnchanged() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 10)}, new BigDecimal("10.00"), false, BUY);
        economy.hasFunds = (u, a) -> true;
        when(inventory.hasItems(any(), eq(ownerInv))).thenReturn(true);
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(true);

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualByComparingTo("10.00");
    }

    @Test
    void adjustBuy_cancelsWhenCannotAffordASingleItem() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 10)}, new BigDecimal("100.00"), false, BUY);
        economy.hasFunds = (u, a) -> false;
        economy.balance = u -> new BigDecimal("5.00"); // price/item=10 → affords 0
        calc.adjustBuy(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.CLIENT_DOES_NOT_HAVE_ENOUGH_MONEY);
    }

    @Test
    void adjustBuy_scalesDownToAffordableAmount() {
        // 10 items @ 100 total = 10/item. Wallet 45 → affords 4 → scaled price 40.00.
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 10)}, new BigDecimal("100.00"), false, BUY);
        economy.hasFunds = (u, a) -> false;
        economy.balance = u -> new BigDecimal("45.00");
        when(inventory.hasItems(any(), eq(ownerInv))).thenReturn(true);
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(true);

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualByComparingTo("40.00");
    }

    @Test
    void adjustBuy_cancelsWhenAffordableAmountRoundsPriceToZero() {
        // 0.04/100 = 0.0004 per item; wallet 0.0004 → affords 1 → scaled 0.0004 → 0.00 at 2dp.
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 100)}, new BigDecimal("0.04"), false, BUY);
        economy.hasFunds = (u, a) -> false;
        economy.balance = u -> new BigDecimal("0.0004");
        calc.adjustBuy(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.CLIENT_DOES_NOT_HAVE_ENOUGH_MONEY);
    }

    // ═══════════════════ adjustBuy — owner stock ═══════════════════

    @Test
    void adjustBuy_scalesToOwnerStock_whenChestPartlyStocked() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 10)}, new BigDecimal("10.00"), false, BUY);
        economy.hasFunds = (u, a) -> true;
        when(inventory.hasItems(any(), eq(ownerInv))).thenReturn(false); // not fully stocked
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 10), 10);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getAmount(any(), eq(ownerInv))).thenReturn(4);
        when(inventory.getItemStacked(any(), eq(4))).thenReturn(new ItemStack[]{item(Material.STONE, 4)});
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(true);

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualByComparingTo("4.00"); // 1.00/item * 4
    }

    @Test
    void adjustBuy_cancelsWhenOwnerHasNoStock() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 10)}, new BigDecimal("10.00"), false, BUY);
        economy.hasFunds = (u, a) -> true;
        when(inventory.hasItems(any(), eq(ownerInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 10), 10);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getAmount(any(), eq(ownerInv))).thenReturn(0); // possessed 0
        when(inventory.getItemStacked(any(), eq(0))).thenReturn(new ItemStack[0]);

        calc.adjustBuy(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_STOCK_IN_CHEST);
    }

    @Test
    void adjustBuy_cancelsWhenOwnerStockScalesPriceToZero() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 100)}, new BigDecimal("0.04"), false, BUY);
        economy.hasFunds = (u, a) -> true;
        when(inventory.hasItems(any(), eq(ownerInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 100), 100);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getAmount(any(), eq(ownerInv))).thenReturn(1); // 0.0004*1 → 0.00
        when(inventory.getItemStacked(any(), eq(1))).thenReturn(new ItemStack[]{item(Material.STONE, 1)});

        calc.adjustBuy(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_STOCK_IN_CHEST);
    }

    @Test
    void adjustBuy_unlimitedOwner_skipsStockCheck() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 10)}, new BigDecimal("10.00"), true, BUY);
        economy.hasFunds = (u, a) -> true;
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(true);
        calc.adjustBuy(ctx);
        assertThat(ctx.isCancelled()).isFalse();
    }

    // ═══════════════════ adjustBuy — client space ═══════════════════

    @Test
    void adjustBuy_scalesToClientSpace_whenInventoryPartlyFull() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 10)}, new BigDecimal("10.00"), true, BUY);
        economy.hasFunds = (u, a) -> true;
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 10), 10);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        when(clientInv.getContents()).thenReturn(new ItemStack[]{null, null});
        when(clientInv.getStorageContents()).thenReturn(new ItemStack[]{null}); // 1 empty slot → holds up to 64
        when(inventory.getItemStacked(any(), anyInt())).thenReturn(new ItemStack[]{item(Material.STONE, 10)});

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
    }

    @Test
    void adjustBuy_cancelsWhenClientHasNoSpace() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 10)}, new BigDecimal("10.00"), true, BUY);
        economy.hasFunds = (u, a) -> true;
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 10), 10);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        when(clientInv.getContents()).thenReturn(new ItemStack[]{null}); // no matching items, free=0
        when(clientInv.getStorageContents()).thenReturn(new ItemStack[]{item(Material.STONE, 64)}); // 0 empty slots
        when(inventory.getItemStacked(any(), anyInt())).thenReturn(new ItemStack[0]);

        calc.adjustBuy(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_SPACE_IN_INVENTORY);
    }

    @Test
    void adjustBuy_cancelsWhenClientSpaceScalesPriceToZero() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 100)}, new BigDecimal("0.04"), true, BUY);
        economy.hasFunds = (u, a) -> true;
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 100), 100);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        when(clientInv.getContents()).thenReturn(new ItemStack[]{null});
        when(clientInv.getStorageContents()).thenReturn(new ItemStack[]{null}); // 1 empty slot
        when(inventory.getItemStacked(any(), anyInt())).thenReturn(new ItemStack[]{item(Material.STONE, 1)});

        calc.adjustBuy(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_SPACE_IN_INVENTORY);
    }

    // ═══════════════════ adjustSell ═══════════════════

    @Test
    void adjustSell_ownerNotEconomicallyActive_skipsShopFundsCheck() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 10)}, new BigDecimal("10.00"), true, SELL);
        economy.ownerEconomicallyActive = false; // unlimited server-less shop
        when(inventory.hasItems(any(), eq(clientInv))).thenReturn(true);
        calc.adjustSell(ctx);
        assertThat(ctx.isCancelled()).isFalse();
    }

    @Test
    void adjustSell_scalesToShopFunds() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 10)}, new BigDecimal("100.00"), false, SELL);
        economy.ownerEconomicallyActive = true;
        economy.hasFunds = (u, a) -> false;
        economy.balance = u -> new BigDecimal("45.00"); // affords 4 @10/item
        when(inventory.hasItems(any(), eq(clientInv))).thenReturn(true);
        when(inventory.fits(any(ItemStack[].class), eq(ownerInv))).thenReturn(true);

        calc.adjustSell(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualByComparingTo("40.00");
    }

    @Test
    void adjustSell_cancelsWhenShopCannotAffordOne() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 10)}, new BigDecimal("100.00"), false, SELL);
        economy.ownerEconomicallyActive = true;
        economy.hasFunds = (u, a) -> false;
        economy.balance = u -> new BigDecimal("5.00"); // affords 0
        calc.adjustSell(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.SHOP_DOES_NOT_HAVE_ENOUGH_MONEY);
    }

    @Test
    void adjustSell_cancelsWhenShopFundsScaleToZero() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 100)}, new BigDecimal("0.04"), false, SELL);
        economy.ownerEconomicallyActive = true;
        economy.hasFunds = (u, a) -> false;
        economy.balance = u -> new BigDecimal("0.0004"); // affords 1 → 0.00
        calc.adjustSell(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.SHOP_DOES_NOT_HAVE_ENOUGH_MONEY);
    }

    @Test
    void adjustSell_scalesToClientStock() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 10)}, new BigDecimal("10.00"), false, SELL);
        economy.ownerEconomicallyActive = true;
        economy.hasFunds = (u, a) -> true;
        when(inventory.hasItems(any(), eq(clientInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 10), 10);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getAmount(any(), eq(clientInv))).thenReturn(4);
        when(inventory.getItemStacked(any(), eq(4))).thenReturn(new ItemStack[]{item(Material.STONE, 4)});
        when(inventory.fits(any(ItemStack[].class), eq(ownerInv))).thenReturn(true);

        calc.adjustSell(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualByComparingTo("4.00");
    }

    @Test
    void adjustSell_cancelsWhenClientHasNoStock() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 10)}, new BigDecimal("10.00"), false, SELL);
        economy.ownerEconomicallyActive = true;
        economy.hasFunds = (u, a) -> true;
        when(inventory.hasItems(any(), eq(clientInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 10), 10);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getAmount(any(), eq(clientInv))).thenReturn(0);
        when(inventory.getItemStacked(any(), eq(0))).thenReturn(new ItemStack[0]);

        calc.adjustSell(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_STOCK_IN_INVENTORY);
    }

    @Test
    void adjustSell_cancelsWhenClientStockScalesToZero() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 100)}, new BigDecimal("0.04"), false, SELL);
        economy.ownerEconomicallyActive = true;
        economy.hasFunds = (u, a) -> true;
        when(inventory.hasItems(any(), eq(clientInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 100), 100);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getAmount(any(), eq(clientInv))).thenReturn(1);
        when(inventory.getItemStacked(any(), eq(1))).thenReturn(new ItemStack[]{item(Material.STONE, 1)});

        calc.adjustSell(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_STOCK_IN_INVENTORY);
    }

    @Test
    void adjustSell_scalesToOwnerSpace() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 10)}, new BigDecimal("10.00"), false, SELL);
        economy.ownerEconomicallyActive = true;
        economy.hasFunds = (u, a) -> true;
        when(inventory.hasItems(any(), eq(clientInv))).thenReturn(true);
        when(inventory.fits(any(ItemStack[].class), eq(ownerInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 10), 10);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        when(ownerInv.getContents()).thenReturn(new ItemStack[]{null});
        when(ownerInv.getStorageContents()).thenReturn(new ItemStack[]{null});
        when(inventory.getItemStacked(any(), anyInt())).thenReturn(new ItemStack[]{item(Material.STONE, 10)});

        calc.adjustSell(ctx);

        assertThat(ctx.isCancelled()).isFalse();
    }

    @Test
    void adjustSell_cancelsWhenOwnerHasNoSpace() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 10)}, new BigDecimal("10.00"), false, SELL);
        economy.ownerEconomicallyActive = true;
        economy.hasFunds = (u, a) -> true;
        when(inventory.hasItems(any(), eq(clientInv))).thenReturn(true);
        when(inventory.fits(any(ItemStack[].class), eq(ownerInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 10), 10);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        when(ownerInv.getContents()).thenReturn(new ItemStack[]{null});
        when(ownerInv.getStorageContents()).thenReturn(new ItemStack[]{item(Material.STONE, 64)}); // 0 empty
        when(inventory.getItemStacked(any(), anyInt())).thenReturn(new ItemStack[0]);

        calc.adjustSell(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_SPACE_IN_CHEST);
    }

    @Test
    void adjustSell_cancelsWhenOwnerSpaceScalesToZero() {
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 100)}, new BigDecimal("0.04"), false, SELL);
        economy.ownerEconomicallyActive = true;
        economy.hasFunds = (u, a) -> true;
        when(inventory.hasItems(any(), eq(clientInv))).thenReturn(true);
        when(inventory.fits(any(ItemStack[].class), eq(ownerInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 100), 100);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        when(ownerInv.getContents()).thenReturn(new ItemStack[]{null});
        when(ownerInv.getStorageContents()).thenReturn(new ItemStack[]{null});
        when(inventory.getItemStacked(any(), anyInt())).thenReturn(new ItemStack[]{item(Material.STONE, 1)});

        calc.adjustSell(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_SPACE_IN_CHEST);
    }

    @Test
    void adjustSell_doesNotThrow_whenAffordableQuotientOverflowsInt() {
        // chestshop/behaviour/0003: getBalance can return Double.MAX_VALUE for an unlimited
        // server-less admin shop. walletMoney / pricePerItem then far exceeds Integer.MAX_VALUE.
        // intValueExact() would throw ArithmeticException and abort the trade on the main thread;
        // the affordable amount must instead clamp to the requested item count (10 here).
        PendingTransaction ctx = ctx(new ItemStack[]{item(Material.STONE, 10)}, new BigDecimal("0.01"), false, SELL);
        economy.ownerEconomicallyActive = true;
        economy.hasFunds = (u, a) -> false; // force the scale-to-affordable branch
        economy.balance = u -> BigDecimal.valueOf(Double.MAX_VALUE); // astronomically large wallet
        when(inventory.hasItems(any(), eq(clientInv))).thenReturn(true);
        when(inventory.fits(any(ItemStack[].class), eq(ownerInv))).thenReturn(true);

        calc.adjustSell(ctx); // must not throw

        // Affordable amount clamped to the 10 requested → full price honoured, not cancelled.
        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualByComparingTo("0.01");
    }

    // ═══════════════════ getCountedItemStack stacking maths (via adjustBuy) ═══════════════════

    @Test
    void getCountedItemStack_mergesPartialStacks_withinMaxStackSize() {
        // Two 30-stacks, afford 60 → merge into one 60 stack (newAmount 60 <= 64).
        ItemStack[] stock = {item(Material.STONE, 30), item(Material.STONE, 30)};
        PendingTransaction ctx = ctx(stock, new BigDecimal("60.00"), false, BUY);
        economy.hasFunds = (u, a) -> false;
        economy.balance = u -> new BigDecimal("60.00"); // 1/item → affords 60
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        when(inventory.getItemsStacked(any(ItemStack.class))).thenAnswer(i -> new ItemStack[]{i.getArgument(0)});
        when(inventory.hasItems(any(), eq(ownerInv))).thenReturn(true);
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(true);

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualByComparingTo("60.00");
    }

    @Test
    void getCountedItemStack_overflowsIntoNewStack_whenExceedingMaxStackSize() {
        // Two 40-stacks, afford 80, max 64 → first stack fills to 64, remainder 16 overflows.
        ItemStack[] stock = {item(Material.STONE, 40), item(Material.STONE, 40)};
        PendingTransaction ctx = ctx(stock, new BigDecimal("80.00"), false, BUY);
        economy.hasFunds = (u, a) -> false;
        economy.balance = u -> new BigDecimal("80.00"); // affords 80
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        when(inventory.getItemsStacked(any(ItemStack.class))).thenAnswer(i -> new ItemStack[]{i.getArgument(0)});
        when(inventory.hasItems(any(), eq(ownerInv))).thenReturn(true);
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(true);

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualByComparingTo("80.00");
    }

    @Test
    void getCountedItemStack_stopsWhenAffordableExceedsStock() {
        // afford more than the total stock: the loop consumes every stack without hitting left<=0.
        ItemStack[] stock = {item(Material.STONE, 10)};
        PendingTransaction ctx = ctx(stock, new BigDecimal("10.00"), false, BUY);
        economy.hasFunds = (u, a) -> false;
        economy.balance = u -> new BigDecimal("1000.00"); // affords 1000, stock only 10
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        when(inventory.getItemsStacked(any(ItemStack.class))).thenAnswer(i -> new ItemStack[]{i.getArgument(0)});
        when(inventory.hasItems(any(), eq(ownerInv))).thenReturn(true);
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(true);

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
    }

    // ═══════════════════ getItemsThatFit branches (via adjustBuy client-space) ═══════════════════

    @Test
    void getItemsThatFit_usesPartialStackSpace_thenEmptySlots() {
        ItemStack[] stock = {item(Material.STONE, 100)};
        PendingTransaction ctx = ctx(stock, new BigDecimal("100.00"), true, BUY);
        economy.hasFunds = (u, a) -> true;
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 100), 100);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        // one partial 40-stack in-inventory → 24 free; plus 2 empty storage slots
        when(clientInv.getContents()).thenReturn(new ItemStack[]{item(Material.STONE, 40), null, null});
        when(clientInv.getStorageContents()).thenReturn(new ItemStack[]{item(Material.STONE, 40), null, null});
        // free=24 + 2 empty slots*64 → all 100 fit; return a single 100 stack.
        when(inventory.getItemStacked(any(), anyInt())).thenReturn(new ItemStack[]{item(Material.STONE, 100)});

        calc.adjustBuy(ctx);

        // free=24, 2 empty slots*64=128 capacity → all 100 fit, no cancel.
        assertThat(ctx.isCancelled()).isFalse();
    }

    @Test
    void getItemsThatFit_cappedByEmptySlots_whenNotEnoughRoom() {
        ItemStack[] stock = {item(Material.STONE, 200)};
        PendingTransaction ctx = ctx(stock, new BigDecimal("200.00"), true, BUY);
        economy.hasFunds = (u, a) -> true;
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 200), 200);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        // no partial stacks (free=0), only 1 empty slot → caps to 64
        when(clientInv.getContents()).thenReturn(new ItemStack[]{null});
        when(clientInv.getStorageContents()).thenReturn(new ItemStack[]{null});
        // free=0, 1 empty slot → capped to 64.
        when(inventory.getItemStacked(any(), anyInt())).thenReturn(new ItemStack[]{item(Material.STONE, 64)});

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse(); // scaled to 64 fit
        assertThat(ctx.getExactPrice()).isEqualByComparingTo("64.00");
    }

    @Test
    void getItemsThatFit_skipsFullItemTypeWithNoEmptySlots() {
        // amount fits entirely inside partial stacks (amount <= free) → the amount>free branch is skipped.
        ItemStack[] stock = {item(Material.STONE, 10)};
        PendingTransaction ctx = ctx(stock, new BigDecimal("10.00"), true, BUY);
        economy.hasFunds = (u, a) -> true;
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 10), 10);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        // partial 40-stack → 24 free, which already covers the 10 requested; no empty slots.
        when(clientInv.getContents()).thenReturn(new ItemStack[]{item(Material.STONE, 40)});
        when(clientInv.getStorageContents()).thenReturn(new ItemStack[]{item(Material.STONE, 40)}); // 0 empty
        // amount (10) <= free (24) → all 10 fit inside the partial stack.
        when(inventory.getItemStacked(any(), anyInt())).thenReturn(new ItemStack[]{item(Material.STONE, 10)});

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
    }

    @Test
    void getCountedItemStack_iteratesPastNonMatchingStack_toMergeMatch() {
        // Stock has a DIRT stack then two STONE stacks: building the second STONE requires
        // iterating past DIRT (no match) to the first STONE (match) — exercises both arcs of
        // the stacking predicate (size-within-max true; material-equal true and false).
        ItemStack[] stock = {item(Material.DIRT, 30), item(Material.STONE, 30), item(Material.STONE, 20)};
        PendingTransaction ctx = ctx(stock, new BigDecimal("80.00"), false, BUY); // 80 items @ 1/item
        economy.hasFunds = (u, a) -> false;
        economy.balance = u -> new BigDecimal("80.00"); // affords all 80
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        when(inventory.getItemsStacked(any(ItemStack.class))).thenAnswer(i -> new ItemStack[]{i.getArgument(0)});
        when(inventory.hasItems(any(), eq(ownerInv))).thenReturn(true);
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(true);

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
    }

    @Test
    void getCountedItemStack_skipsFullStackInList() {
        // A full 64-stack already in the accumulator: the size-within-max predicate is false,
        // so the toAdd remainder starts a fresh stack instead of merging.
        ItemStack[] stock = {item(Material.STONE, 64), item(Material.STONE, 30)};
        PendingTransaction ctx = ctx(stock, new BigDecimal("94.00"), false, BUY); // 94 items @ 1/item
        economy.hasFunds = (u, a) -> false;
        economy.balance = u -> new BigDecimal("94.00"); // affords all 94
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        when(inventory.getItemsStacked(any(ItemStack.class))).thenAnswer(i -> new ItemStack[]{i.getArgument(0)});
        when(inventory.hasItems(any(), eq(ownerInv))).thenReturn(true);
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(true);

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
    }

    @Test
    void getItemsThatFit_cappedToPartialStackSpace_whenNoEmptySlots() {
        // amount (30) > free (24) but there are no empty slots → amount is capped to free (24).
        ItemStack[] stock = {item(Material.STONE, 30)};
        PendingTransaction ctx = ctx(stock, new BigDecimal("30.00"), true, BUY);
        economy.hasFunds = (u, a) -> true;
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 30), 30);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        when(clientInv.getContents()).thenReturn(new ItemStack[]{item(Material.STONE, 40)}); // 24 free
        when(clientInv.getStorageContents()).thenReturn(new ItemStack[]{item(Material.STONE, 40)}); // 0 empty
        when(inventory.getItemStacked(any(), anyInt())).thenReturn(new ItemStack[]{item(Material.STONE, 24)});

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualByComparingTo("24.00"); // 1.00/item * 24
    }

    @Test
    void getItemsThatFit_ignoresNullSlotsEvenWhenEqualityMatchesNull() {
        // Guard branch: if a (hypothetical) equals returns true for a null slot, the explicit
        // null-check still skips it. Force equals to match everything, incl. a null slot.
        lenient().when(materials.equals(any(), any())).thenReturn(true);
        ItemStack[] stock = {item(Material.STONE, 10)};
        PendingTransaction ctx = ctx(stock, new BigDecimal("10.00"), true, BUY);
        economy.hasFunds = (u, a) -> true;
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 10), 10);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        // one real partial stack (contributes free) and a null slot (must be skipped).
        when(clientInv.getContents()).thenReturn(new ItemStack[]{item(Material.STONE, 40), null});
        when(clientInv.getStorageContents()).thenReturn(new ItemStack[]{item(Material.STONE, 40), null}); // 1 empty
        when(inventory.getItemStacked(any(), anyInt())).thenReturn(new ItemStack[]{item(Material.STONE, 10)});

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
    }

    @Test
    void getItemsThatFit_skipsWhenTypeFullAndNoEmptySlots() {
        // free==0 && emptySlots==0 → the item type is skipped entirely, leaving nothing to fill.
        ItemStack[] stock = {item(Material.STONE, 10)};
        PendingTransaction ctx = ctx(stock, new BigDecimal("10.00"), true, BUY);
        economy.hasFunds = (u, a) -> true;
        when(inventory.fits(any(ItemStack[].class), eq(clientInv))).thenReturn(false);
        Map<ItemStack, Integer> counts = new LinkedHashMap<>();
        counts.put(item(Material.STONE, 10), 10);
        when(inventory.getItemCounts(any())).thenReturn(counts);
        when(inventory.getMaxStackSize(any())).thenReturn(64);
        // a full 64-stack (0 free) and no empty slots → nothing fits.
        when(clientInv.getContents()).thenReturn(new ItemStack[]{item(Material.STONE, 64)});
        when(clientInv.getStorageContents()).thenReturn(new ItemStack[]{item(Material.STONE, 64)});
        when(inventory.getItemStacked(any(), anyInt())).thenAnswer(i -> new ItemStack[0]);

        calc.adjustBuy(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_SPACE_IN_INVENTORY);
    }
    /**
     * Hand-written {@link io.paradaux.chestshop.services.EconomyService} double. It can't be a
     * Mockito mock: the interface's {@code bind(TreasuryApi, int, TaxApi)} forces ByteBuddy to load
     * Treasury API types that are deliberately absent from the test runtime. A concrete empty
     * {@code bind} body avoids that. Declared {@code private} so the JUnit Platform discovery scan
     * (which calls {@code getMethods()} on non-private test-source classes) skips it before it would
     * hit the same missing-type resolution. Behaviour is driven through public fields.
     */
    private static final class FakeEconomy implements io.paradaux.chestshop.services.EconomyService {
        boolean ownerEconomicallyActive = true;
        java.util.function.BiPredicate<java.util.UUID, java.math.BigDecimal> hasFunds = (u, a) -> true;
        java.util.function.Function<java.util.UUID, java.math.BigDecimal> balance = u -> java.math.BigDecimal.ZERO;
        boolean hasAccount = true;
        boolean settleResult = true;
        int settleCalls = 0;
        int migrateCalls = 0;
        @Override public void bind(io.paradaux.treasury.api.TreasuryApi treasury, int systemAccountId, io.paradaux.treasury.api.TaxApi taxApi) { /* unused headless */ }
        @Override public String format(java.math.BigDecimal amount) { return amount.toPlainString(); }
        @Override public boolean isOwnerEconomicallyActive(boolean unlimitedOwner) { return ownerEconomicallyActive; }
        @Override public boolean deposit(java.util.UUID target, java.math.BigDecimal amount, org.bukkit.World world) { return true; }
        @Override public boolean withdraw(java.util.UUID target, java.math.BigDecimal amount, org.bukkit.World world) { return true; }
        @Override public boolean hasFunds(java.util.UUID account, java.math.BigDecimal amount) { return hasFunds.test(account, amount); }
        @Override public java.math.BigDecimal getBalance(java.util.UUID account) { return balance.apply(account); }
        @Override public boolean hasAccount(java.util.UUID account) { return hasAccount; }
        @Override public boolean settle(java.math.BigDecimal amount, org.bukkit.entity.Player initiator, java.util.UUID partner, io.paradaux.chestshop.model.Transaction txn) { settleCalls++; return settleResult; }
        @Override public void migrateLegacyBusinessSign(io.paradaux.chestshop.model.Transaction event) { migrateCalls++; }
    }
}
