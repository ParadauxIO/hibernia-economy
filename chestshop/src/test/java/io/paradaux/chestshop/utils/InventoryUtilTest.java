package io.paradaux.chestshop.utils;

import io.paradaux.chestshop.support.ServerTest;
import org.bukkit.Material;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryUtilTest extends ServerTest {

    private static void setLegacyFlag(Boolean v) throws Exception {
        Field f = InventoryUtil.class.getDeclaredField("legacyContents");
        f.setAccessible(true);
        f.set(null, v);
    }

    @Test
    void isEmpty_trueForEmpty_falseWhenAStackIsPresent() {
        assertThat(InventoryUtil.isEmpty(chest(9))).isTrue();
        assertThat(InventoryUtil.isEmpty(chestWith(9, item(Material.DIAMOND, 1)))).isFalse();
    }

    @Test
    void countEmpty_countsTheFreeSlots() {
        Inventory inv = chestWith(9, item(Material.DIAMOND, 1));
        assertThat(InventoryUtil.countEmpty(inv)).isEqualTo(8);
    }

    @Test
    void effectiveSize_isTheStorageLength() {
        assertThat(InventoryUtil.effectiveSize(chest(27))).isEqualTo(27);
    }

    @Test
    void countItems_varargs_sumsAmounts() {
        assertThat(InventoryUtil.countItems(item(Material.DIAMOND, 5), item(Material.DIAMOND, 3)))
                .isEqualTo(8);
    }

    @Test
    void countItems_leftoverMap_sumsAmounts() {
        Map<Integer, ItemStack> leftovers = Map.of(0, item(Material.DIAMOND, 4), 1, item(Material.STONE, 2));
        assertThat(InventoryUtil.countItems(leftovers)).isEqualTo(6);
    }

    @Test
    void getHolder_delegatesToTheInventory() {
        // MockBukkit doesn't implement getHolder(boolean); mock the boundary and assert delegation.
        Inventory inv = mock(Inventory.class);
        InventoryHolder holder = mock(InventoryHolder.class);
        when(inv.getHolder(false)).thenReturn(holder);
        assertThat(InventoryUtil.getHolder(inv, false)).isSameAs(holder);
    }

    @Test
    void getLeftAndRightSide_delegateToTheDoubleChest() {
        DoubleChest dc = mock(DoubleChest.class);
        InventoryHolder left = mock(InventoryHolder.class);
        InventoryHolder right = mock(InventoryHolder.class);
        when(dc.getLeftSide(false)).thenReturn(left);
        when(dc.getRightSide(true)).thenReturn(right);
        assertThat(InventoryUtil.getLeftSide(dc, false)).isSameAs(left);
        assertThat(InventoryUtil.getRightSide(dc, true)).isSameAs(right);
    }

    @Test
    void getStorageContents_fallsBackToGetContents_onServersLackingTheStorageApi() throws Exception {
        Inventory legacy = mock(Inventory.class);
        when(legacy.getStorageContents()).thenThrow(new NoSuchMethodError("no storage API"));
        ItemStack[] contents = {item(Material.DIAMOND, 1)};
        when(legacy.getContents()).thenReturn(contents);
        try {
            setLegacyFlag(null); // force the one-time capability probe to run
            // first call: probe throws NoSuchMethodError -> caught -> legacy path -> getContents()
            assertThat(InventoryUtil.getStorageContents(legacy)).isSameAs(contents);
            // second call: cached legacy flag -> straight to getContents()
            assertThat(InventoryUtil.getStorageContents(legacy)).isSameAs(contents);
        } finally {
            setLegacyFlag(false); // restore the normal cached state for other tests
        }
    }

    @Test
    void isUtilityClass_privateConstructor() throws Exception {
        Constructor<InventoryUtil> ctor = InventoryUtil.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        assertThat(ctor.newInstance()).isNotNull();
    }
}
