package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The deeper meta-comparison branches of {@link MaterialServiceImpl#equals} that the primary
 * (mock-only) test doesn't reach: differing meta classes, a null meta on one side, and the
 * YAML-normalisation fall-back that treats two items as equal only after a dump/reload round-trip.
 * Runs on MockBukkit (real ItemStacks + real YAML) with mock outer items for front-half control.
 *
 * <p>The remaining {@code equals} branches (lines ~102–125) are the SPIGOT-3206/4672/264 workaround
 * paths that compare the YAML-reloaded meta against the <em>original</em> meta. They only diverge on
 * a real Spigot server exhibiting those serialization bugs; MockBukkit's item meta round-trips
 * identically, so those "return true" arcs cannot be produced here.
 */
class MaterialServiceEqualityExtraTest extends ServerTest {

    private MaterialServiceImpl service;

    @BeforeEach
    void wire() {
        ChestShopConfiguration config = TestConfigs.defaults();
        service = new MaterialServiceImpl(config);
    }

    private ItemStack itemWith(Material type, boolean hasMeta, ItemMeta meta, ItemStack cloneResult) {
        ItemStack s = mock(ItemStack.class);
        lenient().when(s.getType()).thenReturn(type);
        lenient().when(s.getAmount()).thenReturn(1);
        lenient().when(s.hasItemMeta()).thenReturn(hasMeta);
        lenient().when(s.getItemMeta()).thenReturn(meta);
        lenient().when(s.clone()).thenReturn(cloneResult);
        return s;
    }

    @Test
    void equals_falseWhenMetaClassesDiffer() {
        ItemStack a = itemWith(Material.STONE, true, mock(ItemMeta.class), null);
        ItemStack b = itemWith(Material.STONE, true, mock(BookMeta.class), null); // different meta class
        when(a.isSimilar(b)).thenReturn(false);
        assertThat(service.equals(a, b)).isFalse();
    }

    @Test
    void equals_falseWhenFirstMetaIsNull() {
        ItemStack a = itemWith(Material.STONE, false, null, null); // hasItemMeta false, meta null
        ItemStack b = itemWith(Material.STONE, true, mock(ItemMeta.class), null);
        when(a.isSimilar(b)).thenReturn(false);
        assertThat(service.equals(a, b)).isFalse();
    }

    @Test
    void equals_falseWhenSecondMetaIsNull() {
        ItemStack a = itemWith(Material.STONE, true, mock(ItemMeta.class), null);
        ItemStack b = itemWith(Material.STONE, false, null, null);
        when(a.isSimilar(b)).thenReturn(false);
        assertThat(service.equals(a, b)).isFalse();
    }

    @Test
    void equals_trueViaYamlNormalisation_whenDumpedItemMatchesTheOtherClone() {
        // Front half says "not similar" and the serialized metas differ, but each item's real clone
        // round-trips through YAML to the same item — the SPIGOT-workaround's first success path.
        ItemMeta ma = mock(ItemMeta.class);
        ItemMeta mb = mock(ItemMeta.class);
        when(ma.serialize()).thenReturn(Map.of("display-name", "A"));
        when(mb.serialize()).thenReturn(Map.of("display-name", "B"));

        ItemStack realA = new ItemStack(Material.DIAMOND);
        ItemStack realB = new ItemStack(Material.DIAMOND);
        ItemStack a = itemWith(Material.DIAMOND, true, ma, realA);
        ItemStack b = itemWith(Material.DIAMOND, true, mb, realB);
        when(a.isSimilar(b)).thenReturn(false);

        assertThat(service.equals(a, b)).isTrue(); // oneDumped.isSimilar(twoCloned)
    }
}
