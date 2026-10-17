package io.paradaux.chestshop.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

/**
 * Real-item coverage for {@link MaterialServiceImpl} on MockBukkit: the config-backed
 * material-name cache and the {@code EXCLUDED_ITEM_ATTRIBUTES}-aware item equality with real
 * {@link ItemStack} serialisation.
 */
class MaterialServiceImplTest extends ServerTest {

    private MaterialServiceImpl material(ChestShopConfiguration config) {
        return new MaterialServiceImpl(config);
    }

    // ---- getMaterial (cache) ----------------------------------------------------

    @Test
    void getMaterial_resolvesCachesAndReReadsFromCache() {
        MaterialServiceImpl m = material(TestConfigs.defaults());
        assertThat(m.getMaterial("Stone")).isEqualTo(Material.STONE);
        // Second call hits the cache branch (same normalised key).
        assertThat(m.getMaterial("Stone")).isEqualTo(Material.STONE);
    }

    @Test
    void getMaterial_returnsNullForUnknown_andDoesNotCache() {
        MaterialServiceImpl m = material(TestConfigs.defaults());
        assertThat(m.getMaterial("Definitely_Not_A_Material")).isNull();
        assertThat(m.getMaterial("Definitely_Not_A_Material")).isNull();
    }

    // ---- equals -----------------------------------------------------------------

    @Test
    void equals_trueForIdenticalPlainItems() {
        MaterialServiceImpl m = material(TestConfigs.defaults());
        assertThat(m.equals(item(Material.DIAMOND, 1), item(Material.DIAMOND, 5)))
                .isTrue();
    }

    @Test
    void equals_falseForDifferentMaterials() {
        MaterialServiceImpl m = material(TestConfigs.defaults());
        assertThat(m.equals(item(Material.DIAMOND, 1), item(Material.DIRT, 1))).isFalse();
    }

    @Test
    void equals_falseForDifferentDurability() {
        MaterialServiceImpl m = material(TestConfigs.defaults());
        ItemStack a = damaged(Material.DIAMOND_SWORD, 5);
        ItemStack b = damaged(Material.DIAMOND_SWORD, 10);
        assertThat(m.equals(a, b)).isFalse();
    }

    @Test
    void equals_bothNull() {
        assertThat(material(TestConfigs.defaults()).equals(null, null)).isTrue();
    }

    @Test
    void equals_oneNull() {
        MaterialServiceImpl m = material(TestConfigs.defaults());
        assertThat(m.equals(item(Material.DIAMOND, 1), null)).isFalse();
        assertThat(m.equals(null, item(Material.DIAMOND, 1))).isFalse();
    }

    @Test
    void equals_trueWhenSerializedMetaDiffersOnlyByExcludedAttribute() {
        // Exclude the display-name attribute: two named items differing only by name are equal.
        ChestShopConfiguration config =
                TestConfigs.with(TestConfigs.defaults(), "excludedItemAttributesRaw", List.of("display-name"));
        MaterialServiceImpl m = material(config);

        ItemStack a = named(Material.DIAMOND, "Alpha");
        ItemStack b = named(Material.DIAMOND, "Beta");
        // isSimilar is false (names differ) so the serialized-meta-with-exclusions path runs.
        assertThat(a.isSimilar(b)).isFalse();
        assertThat(m.equals(a, b)).isTrue();
    }

    @Test
    void equals_falseWhenMetaGenuinelyDiffers_noExclusions() {
        MaterialServiceImpl m = material(TestConfigs.defaults());
        ItemStack a = named(Material.DIAMOND, "Alpha");
        ItemStack b = named(Material.DIAMOND, "Beta");
        assertThat(m.equals(a, b)).isFalse();
    }

    @Test
    void equals_falseWhenOnlyOneHasMeta() {
        MaterialServiceImpl m = material(TestConfigs.defaults());
        ItemStack plain = item(Material.DIAMOND, 1);
        ItemStack named = named(Material.DIAMOND, "Named");
        assertThat(m.equals(plain, named)).isFalse();
        assertThat(m.equals(named, plain)).isFalse();
    }

    @Test
    void equals_trueForTwoUnnamedMetaItemsThatSerializeIdentically() {
        MaterialServiceImpl m = material(TestConfigs.defaults());
        // Same display name -> isSimilar true short-circuit.
        assertThat(m.equals(named(Material.DIAMOND, "Same"), named(Material.DIAMOND, "Same")))
                .isTrue();
    }

    // ---- equals: fall-backs behind a failed isSimilar (mock outer items) -------------

    /**
     * A mock outer stack, so {@code isSimilar} can be forced false and the post-isSimilar
     * fall-backs (meta-class guard, no-meta shortcut, YAML normalisation) are reached.
     */
    private static ItemStack stack(Material type, ItemMeta meta, ItemStack cloneResult) {
        ItemStack s = mock(ItemStack.class);
        lenient().when(s.getType()).thenReturn(type);
        lenient().when(s.getAmount()).thenReturn(1);
        lenient().when(s.hasItemMeta()).thenReturn(meta != null);
        lenient().when(s.getItemMeta()).thenReturn(meta);
        lenient().when(s.clone()).thenReturn(cloneResult);
        return s;
    }

    @Test
    void equals_trueWhenNeitherHasMeta_evenIfIsSimilarDisagrees() {
        ItemStack a = stack(Material.STONE, null, null);
        ItemStack b = stack(Material.STONE, null, null);
        when(a.isSimilar(b)).thenReturn(false);
        assertThat(material(TestConfigs.defaults()).equals(a, b)).isTrue();
    }

    @Test
    void equals_falseWhenMetaClassesDiffer() {
        ItemStack a = stack(Material.STONE, mock(ItemMeta.class), null);
        ItemStack b = stack(Material.STONE, mock(BookMeta.class), null);
        when(a.isSimilar(b)).thenReturn(false);
        assertThat(material(TestConfigs.defaults()).equals(a, b)).isFalse();
    }

    @Test
    void equals_trueViaYamlNormalisation_whenDumpedItemMatchesTheOtherClone() {
        // isSimilar says no and the serialized metas differ, but each item's clone
        // round-trips through YAML to the same item — the SPIGOT-3206/4672 workaround.
        ItemMeta ma = mock(ItemMeta.class);
        ItemMeta mb = mock(ItemMeta.class);
        when(ma.serialize()).thenReturn(Map.of("display-name", "A"));
        when(mb.serialize()).thenReturn(Map.of("display-name", "B"));

        ItemStack a = stack(Material.DIAMOND, ma, new ItemStack(Material.DIAMOND));
        ItemStack b = stack(Material.DIAMOND, mb, new ItemStack(Material.DIAMOND));
        when(a.isSimilar(b)).thenReturn(false);

        assertThat(material(TestConfigs.defaults()).equals(a, b)).isTrue();
    }

    private ItemStack damaged(Material type, int damage) {
        ItemStack stack = new ItemStack(type);
        ItemMeta meta = stack.getItemMeta();
        ((Damageable) meta).setDamage(damage);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack named(Material type, String name) {
        ItemStack stack = new ItemStack(type);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        stack.setItemMeta(meta);
        return stack;
    }
}
