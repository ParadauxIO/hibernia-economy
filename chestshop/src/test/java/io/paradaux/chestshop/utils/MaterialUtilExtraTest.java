package io.paradaux.chestshop.utils;

import io.paradaux.chestshop.support.ServerTest;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Coverage for the item-metadata / durability / fuzzy-material-parse helpers on MaterialUtil. */
class MaterialUtilExtraTest extends ServerTest {

    // ── getDurability ───────────────────────────────────────────────────────
    @Test
    void getDurability_parsesTheDataValue() {
        assertThat(MaterialUtil.getDurability("STONE:5")).isEqualTo(5);
    }

    @Test
    void getDurability_nullWhenNoDataSuffix() {
        assertThat(MaterialUtil.getDurability("STONE")).isNull();
    }

    // ── hasCustomData ───────────────────────────────────────────────────────
    @Test
    void hasCustomData_falseForAnItemWithoutMeta() {
        assertThat(MaterialUtil.hasCustomData(new ItemStack(Material.AIR))).isFalse();
    }

    @Test
    void hasCustomData_trueForAnUndamagedDamageableItem() {
        // Every item meta is Damageable; undamaged -> treated as "custom".
        assertThat(MaterialUtil.hasCustomData(new ItemStack(Material.STONE))).isTrue();
    }

    @Test
    void hasCustomData_falseForADamagedButOtherwisePlainItem() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        org.bukkit.inventory.meta.Damageable meta = (org.bukkit.inventory.meta.Damageable) sword.getItemMeta();
        meta.setDamage(50); // damaged -> skips the undamaged shortcut, falls to the size check
        sword.setItemMeta((ItemMeta) meta);
        assertThat(MaterialUtil.hasCustomData(sword)).isFalse(); // only meta-type + damage
    }

    @Test
    void hasCustomData_trueForADamagedAndNamedItem() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        org.bukkit.inventory.meta.Damageable meta = (org.bukkit.inventory.meta.Damageable) sword.getItemMeta();
        meta.setDamage(50);
        ((ItemMeta) meta).displayName(net.kyori.adventure.text.Component.text("Excalibur"));
        sword.setItemMeta((ItemMeta) meta);
        assertThat(MaterialUtil.hasCustomData(sword)).isTrue(); // meta-type + damage + display-name
    }

    @Test
    void hasCustomData_reachesTheSizeCheck_forNonDamageableMeta() {
        // Every real Paper meta is Damageable; simulate a non-Damageable meta to exercise the
        // instanceof-false path that falls through to the serialized-size check.
        ItemStack item = org.mockito.Mockito.mock(ItemStack.class);
        ItemMeta meta = org.mockito.Mockito.mock(ItemMeta.class); // NOT Damageable
        org.mockito.Mockito.when(item.hasItemMeta()).thenReturn(true);
        org.mockito.Mockito.when(item.getItemMeta()).thenReturn(meta);
        org.mockito.Mockito.when(meta.serialize())
                .thenReturn(java.util.Map.of("meta-type", "ITEM", "extra1", 1, "extra2", 2)); // size 3 > 2
        assertThat(MaterialUtil.hasCustomData(item)).isTrue();
    }

    // ── EnumParser (via resolveMaterial) ────────────────────────────────────
    @Test
    void resolveMaterial_exactName() {
        assertThat(MaterialUtil.resolveMaterial("DIAMOND")).isEqualTo(Material.DIAMOND);
    }

    @Test
    void resolveMaterial_singlePrefixMatch() {
        // "BEDROC" is a prefix of exactly one material.
        assertThat(MaterialUtil.resolveMaterial("bedroc")).isEqualTo(Material.BEDROCK);
    }

    @Test
    void resolveMaterial_multiplePrefixMatches_picksTheClosest() {
        // "DIAMON" prefixes DIAMOND and DIAMOND_* — the closest (shortest) is DIAMOND.
        assertThat(MaterialUtil.resolveMaterial("diamon")).isEqualTo(Material.DIAMOND);
    }

    @Test
    void resolveMaterial_multiPartFuzzyMatch() {
        // "DIA_SWO" doesn't prefix any whole name, but its parts prefix DIAMOND_SWORD's parts.
        assertThat(MaterialUtil.resolveMaterial("dia_swo")).isEqualTo(Material.DIAMOND_SWORD);
    }

    @Test
    void resolveMaterial_nullForNonsense() {
        assertThat(MaterialUtil.resolveMaterial("zzz_not_a_material")).isNull();
    }
}
