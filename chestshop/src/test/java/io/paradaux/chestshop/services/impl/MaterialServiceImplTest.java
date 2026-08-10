package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.chestshop.support.TestConfigs;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(m.equals(item(Material.DIAMOND, 1), item(Material.DIAMOND, 5))).isTrue();
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
    }

    @Test
    void equals_trueWhenSerializedMetaDiffersOnlyByExcludedAttribute() {
        // Exclude the display-name attribute: two named items differing only by name are equal.
        ChestShopConfiguration config = TestConfigs.with(TestConfigs.defaults(),
                "excludedItemAttributesRaw", List.of("display-name"));
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
    }

    @Test
    void equals_trueForTwoUnnamedMetaItemsThatSerializeIdentically() {
        MaterialServiceImpl m = material(TestConfigs.defaults());
        // Same display name -> isSimilar true short-circuit.
        assertThat(m.equals(named(Material.DIAMOND, "Same"), named(Material.DIAMOND, "Same"))).isTrue();
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
