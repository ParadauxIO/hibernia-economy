package io.paradaux.chestshop.model.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.paradaux.chestshop.support.TestConfigs;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Exercises the hand-written adapter getters on {@link ChestShopConfiguration} (the types the
 * Configurator can't bind natively). The Lombok {@code @Getter} accessors are excluded from
 * coverage. Uses {@link TestConfigs} to obtain a real, default-populated config.
 */
class ChestShopConfigurationTest {

    // ── getShopContainers ────────────────────────────────────────────────────────

    @Test
    void getShopContainers_defaultEmptyList_isEmptySet() {
        ChestShopConfiguration cfg = TestConfigs.defaults();
        assertThat(cfg.getShopContainers()).isEmpty();
    }

    @Test
    void getShopContainers_nullRaw_isEmptySet() {
        ChestShopConfiguration cfg = TestConfigs.with(TestConfigs.defaults(), "shopContainersRaw", null);
        assertThat(cfg.getShopContainers()).isEmpty();
    }

    @Test
    void getShopContainers_parsesValidMaterials_andSkipsInvalidOnes() {
        ChestShopConfiguration cfg = TestConfigs.with(
                TestConfigs.defaults(), "shopContainersRaw", List.of("chest", "not_a_real_material", "barrel"));

        assertThat(cfg.getShopContainers()).containsExactlyInAnyOrder(Material.CHEST, Material.BARREL);
    }

    // ── getRemoveEmptyWorlds ─────────────────────────────────────────────────────

    @Test
    void getRemoveEmptyWorlds_nullRaw_isEmptySet() {
        ChestShopConfiguration cfg = TestConfigs.with(TestConfigs.defaults(), "removeEmptyWorldsRaw", null);
        assertThat(cfg.getRemoveEmptyWorlds()).isEmpty();
    }

    @Test
    void getRemoveEmptyWorlds_copiesRawList() {
        ChestShopConfiguration cfg =
                TestConfigs.with(TestConfigs.defaults(), "removeEmptyWorldsRaw", List.of("world", "world_nether"));
        assertThat(cfg.getRemoveEmptyWorlds()).containsExactlyInAnyOrder("world", "world_nether");
    }

    // ── getServerEconomyAccountUuid ──────────────────────────────────────────────

    @Test
    void getServerEconomyAccountUuid_defaultsToZeroUuid() {
        ChestShopConfiguration cfg = TestConfigs.defaults();
        assertThat(cfg.getServerEconomyAccountUuid())
                .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    }

    @Test
    void getServerEconomyAccountUuid_parsesConfiguredValue() {
        UUID id = UUID.randomUUID();
        ChestShopConfiguration cfg =
                TestConfigs.with(TestConfigs.defaults(), "serverEconomyAccountUuidRaw", id.toString());
        assertThat(cfg.getServerEconomyAccountUuid()).isEqualTo(id);
    }

    // ── getExcludedItemAttributes ────────────────────────────────────────────────

    @Test
    void getExcludedItemAttributes_nullRaw_isEmptySet() {
        ChestShopConfiguration cfg = TestConfigs.with(TestConfigs.defaults(), "excludedItemAttributesRaw", null);
        assertThat(cfg.getExcludedItemAttributes()).isEmpty();
    }

    @Test
    void getExcludedItemAttributes_copiesRawList() {
        ChestShopConfiguration cfg = TestConfigs.with(
                TestConfigs.defaults(), "excludedItemAttributesRaw", List.of("Damage", "Enchantments"));
        assertThat(cfg.getExcludedItemAttributes()).containsExactlyInAnyOrder("Damage", "Enchantments");
    }

    // ── tax brackets / item multipliers ─────────────────────────────────────────

    @Test
    void getTaxBrackets_defaultIsEmpty() {
        assertThat(TestConfigs.defaults().getTaxBrackets().isEmpty()).isTrue();
    }

    @Test
    void getTaxBrackets_nullRaw_isEmpty() {
        ChestShopConfiguration cfg = TestConfigs.with(TestConfigs.defaults(), "taxBracketsRaw", null);
        assertThat(cfg.getTaxBrackets().isEmpty()).isTrue();
    }

    @Test
    void getTaxBrackets_parsesAndReusesTheResultUntilTheListChanges() {
        ChestShopConfiguration cfg =
                TestConfigs.with(TestConfigs.defaults(), "taxBracketsRaw", List.of("0=0", "1000=1"));
        var first = cfg.getTaxBrackets();
        assertThat(first.percentFor(new java.math.BigDecimal("2000"))).isEqualByComparingTo("1");
        assertThat(cfg.getTaxBrackets()).isSameAs(first);

        TestConfigs.with(cfg, "taxBracketsRaw", List.of("0=3"));
        assertThat(cfg.getTaxBrackets()).isNotSameAs(first);
        assertThat(cfg.getTaxBrackets().percentFor(java.math.BigDecimal.TEN)).isEqualByComparingTo("3");
    }

    @Test
    void getTaxItemMultipliers_defaultIsEmpty() {
        assertThat(TestConfigs.defaults().getTaxItemMultipliers().isEmpty()).isTrue();
    }

    @Test
    void getTaxItemMultipliers_nullRaw_isEmpty() {
        ChestShopConfiguration cfg = TestConfigs.with(TestConfigs.defaults(), "taxItemMultipliersRaw", null);
        assertThat(cfg.getTaxItemMultipliers().isEmpty()).isTrue();
    }

    @Test
    void getTaxItemMultipliers_parsesAndReusesTheResultUntilTheListChanges() {
        ChestShopConfiguration cfg =
                TestConfigs.with(TestConfigs.defaults(), "taxItemMultipliersRaw", List.of("NETHERITE_INGOT=2"));
        var first = cfg.getTaxItemMultipliers();
        assertThat(first.multiplierFor(java.util.Set.of(), null, "NETHERITE_INGOT"))
                .isEqualByComparingTo("2");
        assertThat(cfg.getTaxItemMultipliers()).isSameAs(first);

        TestConfigs.with(cfg, "taxItemMultipliersRaw", List.of("NETHERITE_INGOT=4"));
        assertThat(cfg.getTaxItemMultipliers().multiplierFor(java.util.Set.of(), null, "NETHERITE_INGOT"))
                .isEqualByComparingTo("4");
    }
}
