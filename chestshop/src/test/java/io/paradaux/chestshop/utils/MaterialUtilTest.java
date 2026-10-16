package io.paradaux.chestshop.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.MaterialService;
import io.paradaux.chestshop.services.impl.MaterialServiceImpl;
import io.paradaux.chestshop.support.ServerTest;
import java.util.LinkedHashSet;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link MaterialUtil}: sign-width name shortening (round-tripped through
 * {@link MaterialServiceImpl#getMaterial}), the fuzzy material parser, the durability/metadata
 * suffix parsers, and the custom-data heuristic.
 *
 * @author Acrobot
 */
class MaterialUtilTest extends ServerTest {

    private MaterialService materialService;

    @BeforeEach
    void setUp() {
        ChestShopConfiguration config = mock(ChestShopConfiguration.class);
        lenient().when(config.getCacheSize()).thenReturn(1000);
        lenient().when(config.getExcludedItemAttributes()).thenReturn(new LinkedHashSet<>());
        materialService = new MaterialServiceImpl(config);
    }

    // ── getShortenedName round-trip ─────────────────────────────────────────

    @Test
    void shortenedName_resolvesBackToEveryMaterial() {
        for (Material material : Material.values()) {
            if (material.name().startsWith("LEGACY_")) {
                continue;
            }
            String shortenedName = MaterialUtil.getShortenedName(material.toString(), MaterialUtil.MAXIMUM_SIGN_WIDTH);
            assertThat(materialService.getMaterial(shortenedName))
                    .as(shortenedName + " did not produce " + material)
                    .isSameAs(material);
        }
    }

    @Test
    void shortenedName_leavingRoomForMetadata_resolvesBackToEveryMaterial() {
        String metaData = "#";
        for (int i = 0; i < 3; i++) {
            metaData += "A";
            int maxWidth = MaterialUtil.MAXIMUM_SIGN_WIDTH - StringUtil.getMinecraftStringWidth(metaData);
            for (Material material : Material.values()) {
                if (material.name().startsWith("LEGACY_")) {
                    continue;
                }
                String shortenedName = MaterialUtil.getShortenedName(material.toString(), maxWidth);
                assertThat(materialService.getMaterial(shortenedName))
                        .as(shortenedName + " with " + metaData + " meta did not produce " + material)
                        .isSameAs(material);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
        "DiamonPicka,  DIAMOND_PICKAXE",
        "Diamon Picka, DIAMOND_PICKAXE",
        "ExpBottle,    EXPERIENCE_BOTTLE",
        "Exp Bottle,   EXPERIENCE_BOTTLE",
    })
    void getMaterial_resolvesAbbreviationsWithAndWithoutSpaces(String materialName, Material expected) {
        assertThat(materialService.getMaterial(materialName)).isEqualTo(expected);
    }

    // ── resolveMaterial (fuzzy enum parser) ─────────────────────────────────

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

    // ── durability / metadata suffixes ──────────────────────────────────────

    @Test
    void getDurability_parsesTheColonSuffixInEitherOrder() {
        assertThat(MaterialUtil.getDurability("STONE")).isNull();
        assertThat(MaterialUtil.getDurability("Stone#a3")).isNull();
        assertThat(MaterialUtil.getDurability("Diamond Sword:1561")).isEqualTo(1561);
        assertThat(MaterialUtil.getDurability("Diamond Sword:1561#2d")).isEqualTo(1561);
        assertThat(MaterialUtil.getDurability("Diamond Sword#2d:1561")).isEqualTo(1561);
        assertThat(MaterialUtil.getDurability("Shovel:250")).isEqualTo(250);
    }

    @Test
    void parseMetadata_parsesTheHashSuffixInEitherOrder() {
        assertThat(MaterialUtil.parseMetadata("Stone")).isNull();
        assertThat(MaterialUtil.parseMetadata("Stone:123")).isNull();
        assertThat(MaterialUtil.parseMetadata("Diamond Sword#asd")).isEqualTo("asd");
        assertThat(MaterialUtil.parseMetadata("Shovel:250#3r9s")).isEqualTo("3r9s");
        assertThat(MaterialUtil.parseMetadata("Shovel#3r9s:250")).isEqualTo("3r9s");
    }

    // ── isEmpty ─────────────────────────────────────────────────────────────

    @Test
    void isEmpty_trueOnlyForNullOrAir() {
        assertThat(MaterialUtil.isEmpty(null)).isTrue();
        assertThat(MaterialUtil.isEmpty(new ItemStack(Material.AIR))).isTrue();
        assertThat(MaterialUtil.isEmpty(new ItemStack(Material.STONE))).isFalse();
    }

    // ── hasCustomData ───────────────────────────────────────────────────────

    @Test
    void hasCustomData_falseForAnItemWithoutMeta() {
        assertThat(MaterialUtil.hasCustomData(new ItemStack(Material.AIR))).isFalse();
    }

    @Test
    void hasCustomData_trueForAnUndamagedItem() {
        // Every Paper item meta is Damageable, and an undamaged one short-circuits to "custom".
        assertThat(MaterialUtil.hasCustomData(new ItemStack(Material.STONE))).isTrue();
    }

    @Test
    void hasCustomData_falseForADamagedButOtherwisePlainItem() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        Damageable meta = (Damageable) sword.getItemMeta();
        meta.setDamage(50);
        sword.setItemMeta(meta);
        assertThat(MaterialUtil.hasCustomData(sword)).isFalse(); // only meta-type + damage
    }

    @Test
    void hasCustomData_trueForADamagedAndNamedItem() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        Damageable meta = (Damageable) sword.getItemMeta();
        meta.setDamage(50);
        ((ItemMeta) meta).displayName(Component.text("Excalibur"));
        sword.setItemMeta(meta);
        assertThat(MaterialUtil.hasCustomData(sword)).isTrue(); // meta-type + damage + display-name
    }
}
