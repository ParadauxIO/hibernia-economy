package io.paradaux.chestshop.services.impl;

import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;
import static io.paradaux.chestshop.model.Transaction.TransactionType.SELL;
import static org.assertj.core.api.Assertions.assertThat;

import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome;
import io.paradaux.chestshop.model.Transaction.TransactionType;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.PartialFillCalculator;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Partial-fill maths against real behaviour: a live MockBukkit server, real {@link ItemStack}s and
 * inventories, and the real {@link InventoryServiceImpl}/{@link MaterialServiceImpl}. Unlike
 * {@link PartialFillCalculatorTest} (which stubs the stacking helpers to drive branches), every
 * test here asserts the exact resized stock (stack-by-stack) and the exact scaled price, including
 * its scale — a mis-counted or mis-priced partial trade is a currency leak.
 *
 * <p>Inventories built with {@link #slots} are 9-slot containers: the given contents first, then
 * the given number of empty slots, and every remaining slot filled with a full stack of
 * {@code COBBLESTONE} (never a traded material), so free space is exactly what the test states.
 */
class PartialFillCalculatorInventoryTest extends ServerTest {

    private FakeEconomy economy;
    private PartialFillCalculator calc;
    private Player client;
    private Account ownerAccount;

    @BeforeEach
    void wire() {
        ChestShopConfiguration config = TestConfigs.defaults(); // PRICE_PRECISION 2, STACK_TO_64 off
        MaterialServiceImpl materials = new MaterialServiceImpl(config);
        InventoryServiceImpl inventories = new InventoryServiceImpl(config, materials);
        economy = new FakeEconomy();
        calc = new PartialFillCalculatorImpl(economy, inventories, materials, config);
        client = player("Client");
        ownerAccount = new Account("Owner", UUID.randomUUID());
        // Deep pockets by default, so only the stock/space under test limits the fill;
        // affordability tests set the exact wallet they need.
        wallet(client.getUniqueId(), "1000000.00");
        wallet(ownerAccount.getUuid(), "1000000.00");
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private static ItemStack[] stacks(ItemStack... stacks) {
        return stacks;
    }

    /** A 9-slot inventory: {@code contents}, then {@code empties} empty slots, the rest full of cobblestone. */
    private Inventory slots(int empties, ItemStack... contents) {
        Inventory inv = server.createInventory(null, 9);
        int slot = 0;
        for (ItemStack content : contents) {
            inv.setItem(slot++, content);
        }
        slot += empties;
        while (slot < 9) {
            inv.setItem(slot++, item(Material.COBBLESTONE, 64));
        }
        return inv;
    }

    /** The stock as "MATERIALxAMOUNT" per stack, so stack structure (not just totals) is asserted. */
    private static List<String> describe(ItemStack[] stock) {
        return Arrays.stream(stock).map(s -> s.getType() + "x" + s.getAmount()).toList();
    }

    private PendingTransaction buy(ItemStack[] stock, String price, Inventory ownerInv, Inventory clientInv) {
        return trade(BUY, stock, price, ownerInv, clientInv, ownerInv == null);
    }

    private PendingTransaction sell(ItemStack[] stock, String price, Inventory ownerInv, Inventory clientInv) {
        return trade(SELL, stock, price, ownerInv, clientInv, ownerInv == null);
    }

    private PendingTransaction trade(
            TransactionType type,
            ItemStack[] stock,
            String price,
            Inventory ownerInv,
            Inventory clientInv,
            boolean unlimited) {
        return new PendingTransaction(
                ownerInv, clientInv, stock, money(price), client, ownerAccount, null, type, unlimited);
    }

    private void wallet(UUID who, String amount) {
        economy.balances.put(who, money(amount));
    }

    // ═══════════════════ BUY — client affordability ═══════════════════

    @Test
    void buy_exactlyEnoughMoney_leavesTheOrderUntouched() {
        ItemStack[] stock = stacks(item(Material.STONE, 10));
        PendingTransaction ctx = buy(stock, "10.00", chestWith(27, item(Material.STONE, 10)), slots(9));
        wallet(client.getUniqueId(), "10.00");

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualTo(money("10.00"));
        assertThat(ctx.getStock()).isSameAs(stock);
        assertThat(describe(ctx.getStock())).containsExactly("STONEx10");
    }

    @Test
    void buy_oneCentShort_dropsToOneFewerItemAtTheExactScaledPrice() {
        PendingTransaction ctx =
                buy(stacks(item(Material.STONE, 10)), "10.00", chestWith(27, item(Material.STONE, 10)), slots(9));
        wallet(client.getUniqueId(), "9.99"); // 1.00/item → affords 9

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualTo(money("9.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx9");
    }

    @Test
    void buy_affordsExactlyOneItem_fillsOne() {
        PendingTransaction ctx =
                buy(stacks(item(Material.STONE, 10)), "100.00", chestWith(27, item(Material.STONE, 10)), slots(9));
        wallet(client.getUniqueId(), "10.00"); // 10.00/item → exactly one

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualTo(money("10.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx1");
    }

    @Test
    void buy_oneCentShortOfASingleItem_cancelsWithoutResizing() {
        ItemStack[] stock = stacks(item(Material.STONE, 10));
        PendingTransaction ctx = buy(stock, "100.00", chestWith(27, item(Material.STONE, 10)), slots(9));
        wallet(client.getUniqueId(), "9.99");

        calc.adjustBuy(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.CLIENT_DOES_NOT_HAVE_ENOUGH_MONEY);
        assertThat(ctx.getExactPrice()).isEqualTo(money("100.00"));
        assertThat(ctx.getStock()).isSameAs(stock);
    }

    @Test
    void buy_partialFill_roundsARepeatingPerItemPriceToPricePrecision() {
        // 10.00 / 3 = 3.333…/item; 7.00 affords 2 → 6.666… → 6.67 at 2dp.
        PendingTransaction ctx =
                buy(stacks(item(Material.STONE, 3)), "10.00", chestWith(27, item(Material.STONE, 3)), slots(9));
        wallet(client.getUniqueId(), "7.00");

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("6.67"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx2");
    }

    @Test
    void buy_partialFill_roundsAnExactHalfCentUp() {
        // 0.05 / 2 = 0.025/item; 0.04 affords 1 → 0.025 → 0.03 (HALF_UP, not banker's 0.02).
        PendingTransaction ctx =
                buy(stacks(item(Material.STONE, 2)), "0.05", chestWith(27, item(Material.STONE, 2)), slots(9));
        wallet(client.getUniqueId(), "0.04");

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("0.03"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx1");
    }

    // ---- getCountedItemStack: trimming a multi-stack order to the affordable count ----

    @Test
    void buy_affordableCountSpanningStacks_keepsFullStackAndTrimsTheNext() {
        PendingTransaction ctx = buy(
                stacks(item(Material.STONE, 64), item(Material.STONE, 64), item(Material.STONE, 64)),
                "192.00",
                chestWith(27, item(Material.STONE, 64), item(Material.STONE, 64), item(Material.STONE, 64)),
                slots(9));
        wallet(client.getUniqueId(), "100.00");

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("100.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx64", "STONEx36");
    }

    @Test
    void buy_affordableCount_mergesPartialStacksIntoOne() {
        // 30 + 30 (+10 unaffordable): the two partial stacks merge into a single 60.
        PendingTransaction ctx = buy(
                stacks(item(Material.STONE, 30), item(Material.STONE, 30), item(Material.STONE, 10)),
                "70.00",
                chestWith(27, item(Material.STONE, 64), item(Material.STONE, 6)),
                slots(9));
        wallet(client.getUniqueId(), "60.00");

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("60.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx60");
    }

    @Test
    void buy_affordableCount_overflowingAMergeStartsANewStack() {
        // 40 + 40 merge → 80 > 64: tops the first up to 64 and carries 16 into a new stack.
        PendingTransaction ctx = buy(
                stacks(item(Material.STONE, 40), item(Material.STONE, 40), item(Material.STONE, 40)),
                "120.00",
                chestWith(27, item(Material.STONE, 64), item(Material.STONE, 56)),
                slots(9));
        wallet(client.getUniqueId(), "80.00");

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("80.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx64", "STONEx16");
    }

    @Test
    void buy_affordableCount_skipsAFullStackAndMergesIntoTheNextPartialOne() {
        // 64 | 10 | 20 (| 5 unaffordable): the 20 must merge into the partial 10, not bounce off
        // the full 64 into a third stack.
        PendingTransaction ctx = buy(
                stacks(
                        item(Material.STONE, 64),
                        item(Material.STONE, 10),
                        item(Material.STONE, 20),
                        item(Material.STONE, 5)),
                "99.00",
                chestWith(27, item(Material.STONE, 64), item(Material.STONE, 35)),
                slots(9));
        wallet(client.getUniqueId(), "94.00");

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("94.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx64", "STONEx30");
    }

    @Test
    void buy_affordableCount_neverMergesDifferentMaterials() {
        PendingTransaction ctx = buy(
                stacks(item(Material.DIRT, 10), item(Material.STONE, 10)),
                "20.00",
                chestWith(27, item(Material.DIRT, 10), item(Material.STONE, 10)),
                slots(9));
        wallet(client.getUniqueId(), "15.00");

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("15.00"));
        assertThat(describe(ctx.getStock())).containsExactly("DIRTx10", "STONEx5");
    }

    @Test
    void buy_affordableCount_respectsMaxStackSize16() {
        assertThat(new ItemStack(Material.ENDER_PEARL).getMaxStackSize()).isEqualTo(16);
        PendingTransaction ctx = buy(
                stacks(item(Material.ENDER_PEARL, 16), item(Material.ENDER_PEARL, 16), item(Material.ENDER_PEARL, 16)),
                "48.00",
                chestWith(
                        27,
                        item(Material.ENDER_PEARL, 16),
                        item(Material.ENDER_PEARL, 16),
                        item(Material.ENDER_PEARL, 16)),
                slots(9));
        wallet(client.getUniqueId(), "20.00");

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("20.00"));
        assertThat(describe(ctx.getStock())).containsExactly("ENDER_PEARLx16", "ENDER_PEARLx4");
    }

    @Test
    void buy_affordableCount_respectsMaxStackSize1() {
        assertThat(new ItemStack(Material.DIAMOND_SWORD).getMaxStackSize()).isEqualTo(1);
        PendingTransaction ctx = buy(
                stacks(
                        item(Material.DIAMOND_SWORD, 1),
                        item(Material.DIAMOND_SWORD, 1),
                        item(Material.DIAMOND_SWORD, 1)),
                "30.00",
                chestWith(
                        27,
                        item(Material.DIAMOND_SWORD, 1),
                        item(Material.DIAMOND_SWORD, 1),
                        item(Material.DIAMOND_SWORD, 1)),
                slots(9));
        wallet(client.getUniqueId(), "20.00");

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("20.00"));
        assertThat(describe(ctx.getStock())).containsExactly("DIAMOND_SWORDx1", "DIAMOND_SWORDx1");
    }

    // ═══════════════════ BUY — owner (chest) stock ═══════════════════

    @Test
    void buy_chestPartlyStocked_scalesStockAndPriceToWhatTheChestHolds() {
        PendingTransaction ctx =
                buy(stacks(item(Material.STONE, 10)), "10.00", chestWith(27, item(Material.STONE, 4)), slots(9));
        wallet(client.getUniqueId(), "1000.00");

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualTo(money("4.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx4");
    }

    @Test
    void buy_chestStockAcrossStacks_isRestackedToTheHeldCount() {
        PendingTransaction ctx = buy(
                stacks(item(Material.STONE, 64), item(Material.STONE, 64)),
                "128.00",
                chestWith(27, item(Material.STONE, 64), item(Material.STONE, 6)),
                slots(9));
        wallet(client.getUniqueId(), "1000.00");

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("70.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx64", "STONEx6");
    }

    @Test
    void buy_freeShop_emptyChest_cancelsAsOutOfStock() {
        // Price 0 means a zero-item fill can't be caught by the rounded-to-zero guard: the
        // possessed<=0 check is the only thing stopping a "successful" trade of nothing.
        PendingTransaction ctx = buy(stacks(item(Material.STONE, 10)), "0.00", chest(27), slots(9));

        calc.adjustBuy(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_STOCK_IN_CHEST);
    }

    // ═══════════════════ BUY — client inventory space (getItemsThatFit) ═══════════════════

    @Test
    void buy_freeShop_clientInventoryFull_cancelsAsNoSpace() {
        PendingTransaction ctx = buy(stacks(item(Material.STONE, 10)), "0.00", null, slots(0));

        calc.adjustBuy(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_SPACE_IN_INVENTORY);
    }

    @Test
    void buy_onlyPartialStackRoom_fillsExactlyTheFreeSpaceInIt() {
        // A 40-stack has 24 free and there are no empty slots: 30 requested → 24.
        PendingTransaction ctx =
                buy(stacks(item(Material.STONE, 30)), "30.00", null, slots(0, item(Material.STONE, 40)));

        calc.adjustBuy(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualTo(money("24.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx24");
    }

    @Test
    void buy_partialStacksOfAnotherMaterial_offerNoRoom() {
        PendingTransaction ctx =
                buy(stacks(item(Material.STONE, 10)), "10.00", null, slots(0, item(Material.DIRT, 40)));

        calc.adjustBuy(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_SPACE_IN_INVENTORY);
    }

    @Test
    void buy_partialStackPlusEmptySlots_capsAtTheirCombinedCapacity() {
        // 24 free in the 40-stack + 2 empty slots × 64 = 152 of the 200 requested.
        PendingTransaction ctx = buy(
                stacks(
                        item(Material.STONE, 64),
                        item(Material.STONE, 64),
                        item(Material.STONE, 64),
                        item(Material.STONE, 8)),
                "200.00",
                null,
                slots(2, item(Material.STONE, 40)));

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("152.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx64", "STONEx64", "STONEx24");
    }

    @Test
    void buy_itemTypeNeedingExactlyTheEmptySlotsLeft_isNotInflatedToFillThem() {
        // STONE 100 needs exactly the 2 empty slots → stays 100 (not 128); DIRT then has no room.
        PendingTransaction ctx = buy(
                stacks(
                        item(Material.STONE, 64),
                        item(Material.STONE, 36),
                        item(Material.DIRT, 64),
                        item(Material.DIRT, 64),
                        item(Material.DIRT, 64),
                        item(Material.DIRT, 8)),
                "300.00",
                null,
                slots(2));

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("100.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx64", "STONEx36");
    }

    @Test
    void buy_mixedTypes_shareTheEmptySlotsInOrder() {
        // 3 empty slots: STONE 100 takes 2, DIRT gets the 1 left → 64 of its 200.
        PendingTransaction ctx = buy(
                stacks(
                        item(Material.STONE, 64),
                        item(Material.STONE, 36),
                        item(Material.DIRT, 64),
                        item(Material.DIRT, 64),
                        item(Material.DIRT, 64),
                        item(Material.DIRT, 8)),
                "300.00",
                null,
                slots(3));

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("164.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx64", "STONEx36", "DIRTx64");
    }

    @Test
    void buy_mixedTypes_partialStackRoomReducesTheEmptySlotsConsumed() {
        // STONE 50: 24 into the partial stack, 26 into ONE empty slot → DIRT keeps the other slot.
        PendingTransaction ctx = buy(
                stacks(
                        item(Material.STONE, 50),
                        item(Material.DIRT, 64),
                        item(Material.DIRT, 64),
                        item(Material.DIRT, 64),
                        item(Material.DIRT, 8)),
                "250.00",
                null,
                slots(2, item(Material.STONE, 40)));

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("114.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx50", "DIRTx64");
    }

    @Test
    void buy_typeThatFitsInPartialStack_isNotInflatedToTheFreeSpace() {
        // DIRT has no room (forcing the resize); STONE 10 fits in 24 free and must stay 10.
        PendingTransaction ctx = buy(
                stacks(item(Material.STONE, 10), item(Material.DIRT, 64), item(Material.DIRT, 36)),
                "110.00",
                null,
                slots(0, item(Material.STONE, 40)));

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("10.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx10");
    }

    @Test
    void buy_clientSpace_respectsMaxStackSize16() {
        // Ender pearls stack to 16: 6 free in the 10-stack + 1 empty slot × 16 = 22 of 40.
        PendingTransaction ctx = buy(
                stacks(item(Material.ENDER_PEARL, 16), item(Material.ENDER_PEARL, 16), item(Material.ENDER_PEARL, 8)),
                "40.00",
                null,
                slots(1, item(Material.ENDER_PEARL, 10)));

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("22.00"));
        assertThat(describe(ctx.getStock())).containsExactly("ENDER_PEARLx16", "ENDER_PEARLx6");
    }

    @Test
    void buy_clientSpace_respectsMaxStackSize1() {
        // Swords don't stack: the held sword offers no room, 2 empty slots → 2 of 5.
        ItemStack[] five = new ItemStack[5];
        Arrays.setAll(five, i -> item(Material.DIAMOND_SWORD, 1));
        PendingTransaction ctx = buy(five, "50.00", null, slots(2, item(Material.DIAMOND_SWORD, 1)));

        calc.adjustBuy(ctx);

        assertThat(ctx.getExactPrice()).isEqualTo(money("20.00"));
        assertThat(describe(ctx.getStock())).containsExactly("DIAMOND_SWORDx1", "DIAMOND_SWORDx1");
    }

    @Test
    void buy_partialStackInPlayerOffHand_isNotFreeSpace() {
        // Free space is counted over the 36 storage slots only — the same slots fits(),
        // countEmpty() and the delivering addItem() use. A partial stack of the item in the
        // off-hand (or a worn armour slot) is not room the goods can go into; counting it used
        // to size a 10-item fill (charged 10.00) that then failed at goods transfer.
        var playerInv = client.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            playerInv.setItem(slot, item(Material.COBBLESTONE, 64));
        }
        playerInv.setItemInOffHand(item(Material.STONE, 40));
        PendingTransaction ctx = buy(stacks(item(Material.STONE, 10)), "10.00", null, null);
        assertThat(ctx.getClientInventory()).isSameAs(playerInv);

        calc.adjustBuy(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_SPACE_IN_INVENTORY);
    }

    // ═══════════════════ SELL ═══════════════════

    @Test
    void sell_shopAffordsExactlyOneItem_fillsOne() {
        PendingTransaction ctx =
                sell(stacks(item(Material.STONE, 10)), "100.00", chest(27), chestWith(27, item(Material.STONE, 10)));
        wallet(ownerAccount.getUuid(), "10.00");

        calc.adjustSell(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualTo(money("10.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx1");
    }

    @Test
    void sell_shopOneCentShortOfASingleItem_cancels() {
        PendingTransaction ctx =
                sell(stacks(item(Material.STONE, 10)), "100.00", chest(27), chestWith(27, item(Material.STONE, 10)));
        wallet(ownerAccount.getUuid(), "9.99");

        calc.adjustSell(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.SHOP_DOES_NOT_HAVE_ENOUGH_MONEY);
    }

    @Test
    void sell_economicallyInactiveOwner_ignoresTheShopBalance() {
        // An unlimited server-less shop pays from nowhere: its (empty) balance must not shrink the fill.
        ItemStack[] stock = stacks(item(Material.STONE, 10));
        PendingTransaction ctx = sell(stock, "100.00", null, chestWith(27, item(Material.STONE, 10)));
        economy.ownerEconomicallyActive = false;
        wallet(ownerAccount.getUuid(), "0.00");

        calc.adjustSell(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualTo(money("100.00"));
        assertThat(ctx.getStock()).isSameAs(stock);
    }

    @Test
    void sell_clientHoldsFewerThanOffered_scalesToWhatTheyHold() {
        PendingTransaction ctx =
                sell(stacks(item(Material.STONE, 10)), "10.00", chest(27), chestWith(27, item(Material.STONE, 4)));
        wallet(ownerAccount.getUuid(), "1000.00");

        calc.adjustSell(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualTo(money("4.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx4");
    }

    @Test
    void sell_freeShop_clientHoldsNone_cancels() {
        PendingTransaction ctx = sell(stacks(item(Material.STONE, 10)), "0.00", chest(27), chest(27));

        calc.adjustSell(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_STOCK_IN_INVENTORY);
    }

    @Test
    void sell_chestHasOnlyPartialStackRoom_scalesStockAndPrice() {
        PendingTransaction ctx = sell(
                stacks(item(Material.STONE, 30)),
                "30.00",
                slots(0, item(Material.STONE, 40)),
                chestWith(27, item(Material.STONE, 30)));
        wallet(ownerAccount.getUuid(), "1000.00");

        calc.adjustSell(ctx);

        assertThat(ctx.isCancelled()).isFalse();
        assertThat(ctx.getExactPrice()).isEqualTo(money("24.00"));
        assertThat(describe(ctx.getStock())).containsExactly("STONEx24");
    }

    @Test
    void sell_freeShop_chestFull_cancels() {
        PendingTransaction ctx =
                sell(stacks(item(Material.STONE, 10)), "0.00", slots(0), chestWith(27, item(Material.STONE, 10)));

        calc.adjustSell(ctx);

        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_SPACE_IN_CHEST);
    }

    /**
     * Balance-backed {@link io.paradaux.chestshop.services.EconomyService} double:
     * {@code hasFunds} is {@code balance >= amount}, so boundaries (exactly enough, one cent
     * short) behave as the real economy does. Private for the same reason as
     * {@link PartialFillCalculatorTest}'s fake: Treasury API types are absent from the test runtime.
     */
    private static final class FakeEconomy implements io.paradaux.chestshop.services.EconomyService {
        final Map<UUID, BigDecimal> balances = new HashMap<>();
        boolean ownerEconomicallyActive = true;

        @Override
        public void bind(
                io.paradaux.treasury.api.TreasuryApi treasury,
                int systemAccountId,
                io.paradaux.treasury.api.TaxApi taxApi) {
            /* unused headless */
        }

        @Override
        public String format(BigDecimal amount) {
            return amount.toPlainString();
        }

        @Override
        public boolean isOwnerEconomicallyActive(boolean unlimitedOwner) {
            return ownerEconomicallyActive;
        }

        @Override
        public boolean deposit(UUID target, BigDecimal amount, org.bukkit.World world) {
            return true;
        }

        @Override
        public boolean withdraw(UUID target, BigDecimal amount, org.bukkit.World world) {
            return true;
        }

        @Override
        public boolean hasFunds(UUID account, BigDecimal amount) {
            return getBalance(account).compareTo(amount) >= 0;
        }

        @Override
        public BigDecimal getBalance(UUID account) {
            return balances.getOrDefault(account, BigDecimal.ZERO);
        }

        @Override
        public boolean hasAccount(UUID account) {
            return true;
        }

        @Override
        public boolean settle(
                BigDecimal amount, Player initiator, UUID partner, io.paradaux.chestshop.model.Transaction txn) {
            return true;
        }

        @Override
        public void migrateLegacyBusinessSign(io.paradaux.chestshop.model.Transaction event) {
            /* unused */
        }
    }
}
