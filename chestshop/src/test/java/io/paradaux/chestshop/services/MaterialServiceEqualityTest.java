package io.paradaux.chestshop.services;

import io.paradaux.chestshop.services.impl.MaterialServiceImpl;
import io.paradaux.chestshop.utils.MaterialUtil;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Targets {@link MaterialUtil#isEmpty(ItemStack)} and
 * {@link MaterialUtil#equals(ItemStack, ItemStack)} — the existing
 * {@code MaterialTest} only exercises the name-shortening path.
 */
@ExtendWith(MockitoExtension.class)
class MaterialServiceEqualityTest {

    @Mock private ItemStack stack;

    private MaterialService materialService;

    @BeforeEach
    void setUp() {
        ChestShopConfiguration config = mock(ChestShopConfiguration.class);
        lenient().when(config.getCacheSize()).thenReturn(1000);
        lenient().when(config.getExcludedItemAttributes()).thenReturn(new LinkedHashSet<>());
        materialService = new MaterialServiceImpl(config);
    }

    @Test
    void isEmpty_trueForNull() {
        assertThat(MaterialUtil.isEmpty(null)).isTrue();
    }

    @Test
    void isEmpty_trueForAirMaterial() {
        lenient().when(stack.getType()).thenReturn(Material.AIR);
        assertThat(MaterialUtil.isEmpty(stack)).isTrue();
    }

    @Test
    void isEmpty_falseForRealItem() {
        // The impl checks (item == null || type == AIR); amount is irrelevant.
        lenient().when(stack.getType()).thenReturn(Material.STONE);
        assertThat(MaterialUtil.isEmpty(stack)).isFalse();
    }

    @Test
    void equals_returnsTrueForBothEmpty() {
        assertThat(materialService.equals(null, null)).isTrue();
    }

    @Test
    void equals_returnsFalseWhenOneIsEmpty() {
        ItemStack real = mock(ItemStack.class);
        lenient().when(real.getType()).thenReturn(Material.STONE);
        lenient().when(real.getAmount()).thenReturn(1);

        assertThat(materialService.equals(real, null)).isFalse();
        assertThat(materialService.equals(null, real)).isFalse();
    }

    @Test
    void equals_returnsFalseForDifferentMaterials() {
        ItemStack a = mock(ItemStack.class);
        ItemStack b = mock(ItemStack.class);
        lenient().when(a.getType()).thenReturn(Material.STONE);
        lenient().when(a.getAmount()).thenReturn(1);
        lenient().when(b.getType()).thenReturn(Material.DIRT);
        lenient().when(b.getAmount()).thenReturn(1);

        assertThat(materialService.equals(a, b)).isFalse();
    }

    @Test
    void equals_returnsTrueForSameMaterialAndNoMeta() {
        ItemStack a = mock(ItemStack.class);
        ItemStack b = mock(ItemStack.class);
        lenient().when(a.getType()).thenReturn(Material.STONE);
        lenient().when(a.getAmount()).thenReturn(1);
        lenient().when(a.hasItemMeta()).thenReturn(false);
        lenient().when(b.getType()).thenReturn(Material.STONE);
        lenient().when(b.getAmount()).thenReturn(1);
        lenient().when(b.hasItemMeta()).thenReturn(false);

        assertThat(materialService.equals(a, b)).isTrue();
    }

    @Test
    void equals_returnsFalseWhenOneSideHasMeta() {
        ItemStack plain = mock(ItemStack.class);
        ItemStack meta  = mock(ItemStack.class);
        ItemMeta itemMeta = mock(ItemMeta.class);

        lenient().when(plain.getType()).thenReturn(Material.STONE);
        lenient().when(plain.getAmount()).thenReturn(1);
        lenient().when(plain.hasItemMeta()).thenReturn(false);

        lenient().when(meta.getType()).thenReturn(Material.STONE);
        lenient().when(meta.getAmount()).thenReturn(1);
        lenient().when(meta.hasItemMeta()).thenReturn(true);
        lenient().when(meta.getItemMeta()).thenReturn(itemMeta);

        assertThat(materialService.equals(plain, meta)).isFalse();
    }
}
