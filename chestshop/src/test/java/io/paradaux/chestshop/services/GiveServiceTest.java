package io.paradaux.chestshop.services;

import io.paradaux.chestshop.services.impl.GiveServiceImpl;
import io.paradaux.chestshop.support.ServerTest;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the {@code /chestshop give} core (PAR-323): the item-code parse and quantity stamp that
 * used to live inline in the command handler. Uses a real MockBukkit server so real
 * {@link ItemStack}s (and {@code Material.AIR} emptiness) behave for real; {@link ItemService}
 * is mocked so the test targets only the give logic, not code resolution.
 */
class GiveServiceTest extends ServerTest {

    @Test
    void resolveGift_stampsQuantityOnParsedItem() {
        ItemService items = mock(ItemService.class);
        when(items.parse("REDSTONE_BLOCK")).thenReturn(new ItemStack(Material.REDSTONE_BLOCK, 1));

        GiveService give = new GiveServiceImpl(items);

        ItemStack result = give.resolveGift("REDSTONE_BLOCK", 17);

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(Material.REDSTONE_BLOCK);
        assertThat(result.getAmount()).isEqualTo(17);
    }

    @Test
    void resolveGift_nullForUnparseableCode() {
        ItemService items = mock(ItemService.class);
        when(items.parse("NOT_AN_ITEM")).thenReturn(null);

        GiveService give = new GiveServiceImpl(items);

        assertThat(give.resolveGift("NOT_AN_ITEM", 1)).isNull();
    }

    @Test
    void resolveGift_nullForAirItem() {
        ItemService items = mock(ItemService.class);
        when(items.parse("air")).thenReturn(new ItemStack(Material.AIR));

        GiveService give = new GiveServiceImpl(items);

        assertThat(give.resolveGift("air", 5)).isNull();
    }
}
