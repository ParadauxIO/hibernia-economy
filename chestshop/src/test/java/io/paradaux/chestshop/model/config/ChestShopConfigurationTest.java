package io.paradaux.chestshop.model.config;

import io.paradaux.chestshop.support.TestConfigs;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
        ChestShopConfiguration cfg = TestConfigs.with(TestConfigs.defaults(), "shopContainersRaw",
                List.of("chest", "not_a_real_material", "barrel"));

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
        ChestShopConfiguration cfg = TestConfigs.with(TestConfigs.defaults(), "removeEmptyWorldsRaw",
                List.of("world", "world_nether"));
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
        ChestShopConfiguration cfg = TestConfigs.with(TestConfigs.defaults(),
                "serverEconomyAccountUuidRaw", id.toString());
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
        ChestShopConfiguration cfg = TestConfigs.with(TestConfigs.defaults(), "excludedItemAttributesRaw",
                List.of("Damage", "Enchantments"));
        assertThat(cfg.getExcludedItemAttributes()).containsExactlyInAnyOrder("Damage", "Enchantments");
    }
}
