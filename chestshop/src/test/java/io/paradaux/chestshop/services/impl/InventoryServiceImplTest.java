package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real inventory maths on a live MockBukkit server: {@link InventoryServiceImpl} over the real
 * {@link MaterialServiceImpl}, exercising amount/capacity/fit queries, add/remove/transfer and
 * STACK_TO_64-aware stacking with real {@link ItemStack}s and inventories.
 *
 * <p>MockBukkit inventories must be a multiple of 9 (9..54) slots, so scenarios that need a
 * constrained matching capacity fill the surplus slots with STONE "blocker" stacks (non-matching,
 * full — they contribute no diamond capacity).
 */
class InventoryServiceImplTest extends ServerTest {

    private ChestShopConfiguration config;
    private InventoryServiceImpl inv;

    @BeforeEach
    void wire() {
        config = TestConfigs.defaults();
        inv = new InventoryServiceImpl(config, new MaterialServiceImpl(config));
    }

    /** A 9-slot chest whose slot 0 holds {@code first} and slots 1..8 are full STONE blockers. */
    private Inventory constrained(ItemStack first) {
        Inventory c = chest(9);
        c.setItem(0, first);
        for (int i = 1; i < 9; i++) {
            c.setItem(i, item(Material.STONE, 64));
        }
        return c;
    }

    /** A 9-slot chest completely full of STONE. */
    private Inventory fullChest() {
        Inventory c = chest(9);
        for (int i = 0; i < 9; i++) {
            c.setItem(i, item(Material.STONE, 64));
        }
        return c;
    }

    // ---- getAmount --------------------------------------------------------------

    @Test
    void getAmount_zeroWhenAbsent() {
        assertThat(inv.getAmount(item(Material.DIAMOND, 1), chest(9))).isZero();
    }

    @Test
    void getAmount_sumsMatchingStacks_andSkipsNonMatching() {
        Inventory c = chestWith(27, item(Material.DIAMOND, 10), item(Material.DIRT, 5), item(Material.DIAMOND, 3));
        assertThat(inv.getAmount(item(Material.DIAMOND, 1), c)).isEqualTo(13);
    }

    @Test
    void getAmount_infiniteWhenInventoryTypeNull() {
        Inventory c = mock(Inventory.class);
        when(c.contains(Material.DIAMOND)).thenReturn(true);
        when(c.getType()).thenReturn(null);
        assertThat(inv.getAmount(item(Material.DIAMOND, 1), c)).isEqualTo(Integer.MAX_VALUE);
    }

    // ---- getRemainingCapacity ---------------------------------------------------

    @Test
    void getRemainingCapacity_zeroForNullInventory() {
        assertThat(inv.getRemainingCapacity(item(Material.DIAMOND, 1), null)).isZero();
    }

    @Test
    void getRemainingCapacity_countsEmptyAndPartialSlots() {
        Inventory c = chest(9);
        c.setItem(0, item(Material.DIAMOND, 60)); // 4 free
        for (int i = 1; i < 8; i++) {
            c.setItem(i, item(Material.STONE, 64)); // blockers
        }
        // slot 8 empty -> 64 free
        assertThat(inv.getRemainingCapacity(item(Material.DIAMOND, 1), c)).isEqualTo(64 + 4);
    }

    @Test
    void getRemainingCapacity_clampsOverfullSlotToZero() {
        Inventory c = constrained(item(Material.DIAMOND, 100)); // overfull matching slot
        assertThat(inv.getRemainingCapacity(item(Material.DIAMOND, 1), c)).isZero();
    }

    // ---- hasItems ---------------------------------------------------------------

    @Test
    void hasItems_trueWhenEnough_falseWhenShort() {
        Inventory c = chestWith(27, item(Material.DIAMOND, 10));
        assertThat(inv.hasItems(new ItemStack[]{item(Material.DIAMOND, 5)}, c)).isTrue();
        assertThat(inv.hasItems(new ItemStack[]{item(Material.DIAMOND, 20)}, c)).isFalse();
    }

    // ---- fits -------------------------------------------------------------------

    @Test
    void fits_array_trueThenFalse() {
        assertThat(inv.fits(new ItemStack[]{item(Material.DIAMOND, 10)}, chest(9))).isTrue();
        assertThat(inv.fits(new ItemStack[]{item(Material.DIAMOND, 10)}, fullChest())).isFalse();
    }

    @Test
    void fits_singleArgDelegates() {
        assertThat(inv.fits(item(Material.DIAMOND, 5), chest(9))).isTrue();
    }

    @Test
    void fits_trueImmediatelyForInfiniteSizedInventory() {
        Inventory c = mock(Inventory.class);
        when(c.getSize()).thenReturn(Integer.MAX_VALUE);
        assertThat(inv.fits(item(Material.DIAMOND, 1), 5, c)).isTrue();
    }

    @Test
    void fits_stopsEarlyOnceRoomFound_andSkipsNonMatching() {
        Inventory c = chest(9);
        c.setItem(0, item(Material.DIRT, 64));     // non-matching, skipped
        c.setItem(1, item(Material.DIAMOND, 60));  // 4 room -> enough for 4
        for (int i = 2; i < 9; i++) {
            c.setItem(i, item(Material.STONE, 64));
        }
        assertThat(inv.fits(item(Material.DIAMOND, 4), c)).isTrue();
    }

    @Test
    void fits_falseWhenPartialMatchingSlotsInsufficient() {
        Inventory c = constrained(item(Material.DIAMOND, 60)); // only 4 room
        assertThat(inv.fits(item(Material.DIAMOND, 5), c)).isFalse();
    }

    // ---- transfer ---------------------------------------------------------------

    @Test
    void transfer_zeroWhenNothingRequested() {
        ItemStack empty = mock(ItemStack.class);
        when(empty.getAmount()).thenReturn(0);
        assertThat(inv.transfer(empty, chest(9), chest(9))).isZero();
    }

    @Test
    void transfer_movesAll_whenTargetHasRoom() {
        Inventory src = chestWith(27, item(Material.DIRT, 4), item(Material.DIAMOND, 32));
        Inventory tgt = chest(27);
        assertThat(inv.transfer(item(Material.DIAMOND, 32), src, tgt)).isZero();
        assertThat(count(tgt, Material.DIAMOND)).isEqualTo(32);
    }

    @Test
    void transfer_returnsLeftover_whenSingleSlotOverflows() {
        Inventory src = chestWith(27, item(Material.DIAMOND, 64));
        Inventory tgt = constrained(item(Material.DIAMOND, 56)); // only 8 room
        int leftover = inv.transfer(item(Material.DIAMOND, 64), src, tgt);
        assertThat(leftover).isEqualTo(56);
        assertThat(count(src, Material.DIAMOND)).isEqualTo(56); // source slot restored
    }

    @Test
    void transfer_accumulatesLeftover_acrossPartialSourceSlot() {
        // Source slot smaller than requested (currentItem.amount < amount) AND target overflows.
        Inventory src = chestWith(27, item(Material.DIAMOND, 10));
        Inventory tgt = constrained(item(Material.DIAMOND, 61)); // 3 room
        int leftover = inv.transfer(item(Material.DIAMOND, 15), src, tgt);
        // clone(10) added -> 7 leftover; remaining amount was 5 (>0) so amount becomes 12.
        assertThat(leftover).isEqualTo(12);
    }

    // ---- add / remove -----------------------------------------------------------

    @Test
    void add_zeroWhenNothingToAdd() {
        ItemStack empty = mock(ItemStack.class);
        when(empty.getAmount()).thenReturn(0);
        assertThat(inv.add(empty, chest(9), 64)).isZero();
    }

    @Test
    void add_vanillaPath_whenMaxStackMatches() {
        Inventory c = chest(27);
        assertThat(inv.add(item(Material.DIAMOND, 10), c, new ItemStack(Material.DIAMOND).getMaxStackSize())).isZero();
        assertThat(count(c, Material.DIAMOND)).isEqualTo(10);
    }

    @Test
    void add_vanillaPath_reportsLeftoverWhenFull() {
        int left = inv.add(item(Material.DIAMOND, 5), fullChest(), new ItemStack(Material.DIAMOND).getMaxStackSize());
        assertThat(left).isEqualTo(5);
    }

    @Test
    void add_manualPath_customMaxStackSize_fillsEmptyAndTopsUp() {
        Inventory c = chest(9);
        c.setItem(0, item(Material.DIAMOND, 3)); // partial matching slot to top up
        // slot 1 empty; slots 2..8 blockers so the overflow spills.
        for (int i = 2; i < 9; i++) {
            c.setItem(i, item(Material.STONE, 64));
        }
        // maxStackSize 5: top slot0 to 5 (+2), put 5 into slot1, 1 left over (no more room).
        int left = inv.add(item(Material.DIAMOND, 8), c, 5);
        assertThat(left).isEqualTo(1);
        assertThat(count(c, Material.DIAMOND)).isEqualTo(10);
    }

    @Test
    void transferWithCustomMaxStack_usesManualAdd() {
        Inventory src = chestWith(27, item(Material.DIAMOND, 6));
        Inventory tgt = chest(27);
        assertThat(inv.transfer(item(Material.DIAMOND, 6), src, tgt, 5)).isZero();
        assertThat(count(tgt, Material.DIAMOND)).isEqualTo(6);
    }

    @Test
    void remove_takesItems_andReportsRemainderWhenShort() {
        Inventory c = chestWith(27, item(Material.DIAMOND, 4));
        assertThat(inv.remove(item(Material.DIAMOND, 3), c)).isZero();
        assertThat(count(c, Material.DIAMOND)).isEqualTo(1);
        assertThat(inv.remove(item(Material.DIAMOND, 10), c)).isEqualTo(9); // only 1 present
    }

    // ---- getItemCounts ----------------------------------------------------------

    @Test
    void getItemCounts_emptyForNullOrEmpty() {
        assertThat(inv.getItemCounts((ItemStack[]) null)).isEmpty();
        assertThat(inv.getItemCounts(new ItemStack[0])).isEmpty();
    }

    @Test
    void getItemCounts_singleItem() {
        Map<ItemStack, Integer> counts = inv.getItemCounts(item(Material.DIAMOND, 7));
        assertThat(counts).containsValue(7).hasSize(1);
    }

    @Test
    void getItemCounts_mergesEqualAndSeparatesDistinct() {
        Map<ItemStack, Integer> counts = inv.getItemCounts(
                item(Material.DIAMOND, 3), item(Material.DIRT, 2), item(Material.DIAMOND, 4));
        assertThat(counts).hasSize(2).containsValue(7).containsValue(2);
    }

    // ---- getMaxStackSize --------------------------------------------------------

    @Test
    void getMaxStackSize_honoursStackTo64() {
        assertThat(inv.getMaxStackSize(item(Material.DIAMOND, 1)))
                .isEqualTo(new ItemStack(Material.DIAMOND).getMaxStackSize());

        ChestShopConfiguration stack64 = TestConfigs.with(TestConfigs.defaults(), "stackTo64", true);
        InventoryServiceImpl inv64 = new InventoryServiceImpl(stack64, new MaterialServiceImpl(stack64));
        assertThat(inv64.getMaxStackSize(item(Material.DIAMOND, 1))).isEqualTo(64);
    }

    // ---- stacking ---------------------------------------------------------------

    @Test
    void getItemsStacked_splitsIntoFullStacks() {
        ItemStack[] stacked = inv.getItemsStacked(item(Material.DIAMOND, 130));
        int total = 0;
        for (ItemStack s : stacked) {
            total += s.getAmount();
            assertThat(s.getAmount()).isLessThanOrEqualTo(64);
        }
        assertThat(total).isEqualTo(130);
        assertThat(stacked).hasSize(3); // 64 + 64 + 2
    }

    @Test
    void getItemStacked_singlePartialStack() {
        ItemStack[] stacked = inv.getItemStacked(item(Material.DIAMOND, 30), 30);
        assertThat(stacked).hasSize(1);
        assertThat(stacked[0].getAmount()).isEqualTo(30);
    }

    @Test
    void getItemsStacked_mergesLoosePartialsThenSpills() {
        // Two partial stacks of the same item: the second tops up the first before spilling.
        ItemStack[] stacked = inv.getItemsStacked(item(Material.DIAMOND, 40), item(Material.DIAMOND, 40));
        int total = 0;
        for (ItemStack s : stacked) {
            total += s.getAmount();
        }
        assertThat(total).isEqualTo(80);
        assertThat(stacked).hasSize(2); // 64 + 16
    }

    @Test
    void getItemsStacked_zeroMaxStackSizeYieldsNothing() {
        // A max stack size of 0 (via a mock item) makes the stackItems guard return immediately.
        ItemStack zeroStack = mock(ItemStack.class);
        when(zeroStack.getAmount()).thenReturn(1);
        when(zeroStack.getMaxStackSize()).thenReturn(0);
        assertThat(inv.getItemsStacked(zeroStack)).isEmpty();
    }

    // ---- equals-but-not-isSimilar paths (EXCLUDED_ITEM_ATTRIBUTES) --------------

    /** An inventory service whose equality ignores display-name (so named==plain by equals). */
    private InventoryServiceImpl exclInv() {
        ChestShopConfiguration cfg = TestConfigs.with(TestConfigs.defaults(),
                "excludedItemAttributesRaw", java.util.List.of("display-name"));
        return new InventoryServiceImpl(cfg, new MaterialServiceImpl(cfg));
    }

    private ItemStack named(Material type, int amount, String name) {
        ItemStack s = item(type, amount);
        var meta = s.getItemMeta();
        meta.setDisplayName(name);
        s.setItemMeta(meta);
        return s;
    }

    @Test
    void getAmount_skipsSameTypeButUnequalStacks() {
        // Two diamond stacks; one damaged so equals() rejects it -> the continue branch.
        ItemStack damaged = item(Material.DIAMOND_SWORD, 1);
        var meta = damaged.getItemMeta();
        ((org.bukkit.inventory.meta.Damageable) meta).setDamage(5);
        damaged.setItemMeta(meta);
        Inventory c = chestWith(27, item(Material.DIAMOND_SWORD, 1), damaged);
        // querying by the pristine sword counts only the pristine one.
        assertThat(inv.getAmount(item(Material.DIAMOND_SWORD, 1), c)).isEqualTo(1);
    }

    @Test
    void add_mergesViaEqualityWhenAddItemLeavesLeftover() {
        InventoryServiceImpl service = exclInv();
        Inventory c = chest(9);
        c.setItem(0, named(Material.DIAMOND, 60, "Fancy")); // 4 room by equality
        for (int i = 1; i < 9; i++) {
            c.setItem(i, item(Material.STONE, 64));
        }
        // addItem (isSimilar) won't stack a plain diamond onto the named one, and there are no
        // empty slots -> leftover; addManually then merges via equals -> nothing left over.
        int left = service.add(item(Material.DIAMOND, 4), c, new ItemStack(Material.DIAMOND).getMaxStackSize());
        assertThat(left).isZero();
    }

    @Test
    void remove_removesViaEqualityWhenRemoveItemLeavesLeftover() {
        InventoryServiceImpl service = exclInv();
        Inventory c = chest(9);
        c.setItem(0, named(Material.DIAMOND, 4, "Fancy"));
        // removeItem (isSimilar) won't match a plain diamond; removeManually merges via equals.
        int left = service.remove(item(Material.DIAMOND, 4), c);
        assertThat(left).isZero();
    }

    @Test
    void addManually_skipsFullMatchingSlot() {
        Inventory c = chest(9);
        c.setItem(0, item(Material.DIAMOND, 5)); // already at the custom max stack size
        // slot 1 empty; add with maxStackSize 5 must skip the full slot 0 and use slot 1.
        int left = inv.add(item(Material.DIAMOND, 3), c, 5);
        assertThat(left).isZero();
        assertThat(count(c, Material.DIAMOND)).isEqualTo(8);
    }

    @Test
    void getItemsStacked_fullyMergesPartial_breaksEarly() {
        InventoryServiceImpl service = exclInv();
        // named40 then plain24: the plain fully merges into the named (room 24) -> break, amount 0.
        ItemStack[] stacked = service.getItemsStacked(named(Material.DIAMOND, 40, "A"), item(Material.DIAMOND, 24));
        int total = 0;
        for (ItemStack s : stacked) {
            total += s.getAmount();
        }
        assertThat(total).isEqualTo(64);
        assertThat(stacked).hasSize(1);
    }

    @Test
    void getItemStacked_exactMultipleLeavesNoRemainder() {
        // 128 = 2 * 64 exactly: the split loop runs and the trailing-remainder branch is skipped.
        ItemStack[] stacked = inv.getItemStacked(item(Material.DIAMOND, 1), 128);
        assertThat(stacked).hasSize(2);
        for (ItemStack s : stacked) {
            assertThat(s.getAmount()).isEqualTo(64);
        }
    }

    @Test
    void fits_trueWhenRoomIsOnlyInTheLastSlot() {
        // Matching partial slot is last, so left reaches 0 only after the loop ends (final return).
        Inventory c = chest(9);
        for (int i = 0; i < 8; i++) {
            c.setItem(i, item(Material.STONE, 64));
        }
        c.setItem(8, item(Material.DIAMOND, 60)); // 4 room, last slot
        assertThat(inv.fits(item(Material.DIAMOND, 4), c)).isTrue();
    }

    @Test
    void addManually_skipsPartialSlotOfDifferentItem() {
        Inventory c = chest(9);
        c.setItem(0, item(Material.STONE, 3)); // partial but non-matching -> equals false, skipped
        int left = inv.add(item(Material.DIAMOND, 2), c, 5);
        assertThat(left).isZero();
        assertThat(count(c, Material.DIAMOND)).isEqualTo(2);
    }

    @Test
    void removeManually_skipsNonMatchingOccupiedSlot() {
        InventoryServiceImpl service = exclInv();
        Inventory c = chest(9);
        c.setItem(0, item(Material.STONE, 64));            // occupied, non-matching -> equals false
        c.setItem(1, named(Material.DIAMOND, 4, "Fancy")); // matched via equality
        int left = service.remove(item(Material.DIAMOND, 4), c);
        assertThat(left).isZero();
    }

    @Test
    void getItemsStacked_skipsUnequalAndFullStacks() {
        // dirt vs diamond -> equals false; a full diamond stack -> amount>=max, both skip the merge.
        ItemStack[] stacked = inv.getItemsStacked(
                item(Material.DIAMOND, 64), item(Material.DIRT, 10), item(Material.DIAMOND, 5));
        int diamonds = 0, dirt = 0;
        for (ItemStack s : stacked) {
            if (s.getType() == Material.DIAMOND) {
                diamonds += s.getAmount();
            } else {
                dirt += s.getAmount();
            }
        }
        assertThat(diamonds).isEqualTo(69);
        assertThat(dirt).isEqualTo(10);
    }

    private static int count(Inventory inv, Material m) {
        int n = 0;
        for (ItemStack s : inv.getContents()) {
            if (s != null && s.getType() == m) {
                n += s.getAmount();
            }
        }
        return n;
    }
}
