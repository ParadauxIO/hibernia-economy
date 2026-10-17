package io.paradaux.chestshop.model.tax;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ItemTaxMultipliersTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static ItemTaxMultipliers sample() {
        return ItemTaxMultipliers.parse(
                List.of("NETHERITE_INGOT=2", "PAPER=5", "nexo:ruby_sword=3", "Diamond Sword#3a=4", "Iron Ingot=0.5"),
                w -> {});
    }

    @Test
    void parsesEntriesCaseInsensitively() {
        List<String> warnings = new ArrayList<>();
        ItemTaxMultipliers m =
                ItemTaxMultipliers.parse(List.of("NETHERITE_INGOT=2", " nexo:Ruby = 1.5 "), warnings::add);

        assertThat(m.isEmpty()).isFalse();
        assertThat(m.multiplierFor(Set.of(), null, "netherite_ingot")).isEqualByComparingTo("2");
        assertThat(m.multiplierFor(Set.of("NEXO:ruby"), null, "PAPER")).isEqualByComparingTo("1.5");
        assertThat(warnings).isEmpty();
    }

    @Test
    void skipsInvalidEntriesWithAWarningEach() {
        List<String> warnings = new ArrayList<>();
        ItemTaxMultipliers m = ItemTaxMultipliers.parse(
                Arrays.asList("STONE", "=2", "DIRT=abc", "SAND=-1", null, "GRAVEL=0"), warnings::add);

        assertThat(m.multiplierFor(Set.of(), null, "GRAVEL")).isEqualByComparingTo("0");
        assertThat(m.multiplierFor(Set.of(), null, "SAND")).isEqualByComparingTo("1");
        assertThat(warnings).hasSize(4);
        assertThat(warnings.get(0)).contains("<item>=<multiplier>");
        assertThat(warnings.get(1)).contains("<item>=<multiplier>");
        assertThat(warnings.get(2)).contains("no valid multiplier");
        assertThat(warnings.get(3)).contains("negative");
    }

    @Test
    void emptyOrMissingConfigMeansNoMultipliers() {
        assertThat(ItemTaxMultipliers.parse(null, w -> {}).isEmpty()).isTrue();
        assertThat(ItemTaxMultipliers.parse(List.of(), w -> {}).isEmpty()).isTrue();
    }

    @Test
    void matchesPlainItemsByMaterial() {
        assertThat(sample().multiplierFor(Set.of(), "Netherite Ingot", "NETHERITE_INGOT"))
                .isEqualByComparingTo("2");
    }

    @Test
    void matchesCustomItemsByTheirId() {
        assertThat(sample().multiplierFor(Set.of("nexo:ruby_sword"), "ruby_sword", "PAPER"))
                .isEqualByComparingTo("3");
    }

    @Test
    void customItemsAreNotMatchedByTheirBaseMaterial() {
        assertThat(sample().multiplierFor(Set.of("nexo:emerald_coin"), "emerald_coin", "PAPER"))
                .isEqualByComparingTo("1");
    }

    @Test
    void customItemsAreNotMatchedThroughTheirMetadataCode() {
        // Without a resolver a custom paper item's code is "Paper#2b"; stripping the metadata
        // must not turn it into PAPER.
        assertThat(sample().multiplierFor(Set.of("nexo:emerald_coin"), "Paper#2b", "PAPER"))
                .isEqualByComparingTo("1");
    }

    @Test
    void anExactCodeStillMatchesACustomItem() {
        ItemTaxMultipliers m = ItemTaxMultipliers.parse(List.of("Paper#2b=7"), w -> {});
        assertThat(m.multiplierFor(Set.of("nexo:emerald_coin"), "Paper#2b", "PAPER"))
                .isEqualByComparingTo("7");
    }

    @Test
    void matchesItemCodesWithOrWithoutMetadataForPlainItems() {
        assertThat(sample().multiplierFor(Set.of(), "Diamond Sword#3a", "DIAMOND_SWORD"))
                .isEqualByComparingTo("4");
        assertThat(sample().multiplierFor(Set.of(), "Iron Ingot#7c", "IRON_INGOT"))
                .isEqualByComparingTo("0.5");
    }

    @Test
    void customIdsWinOverItemCodes() {
        ItemTaxMultipliers m = ItemTaxMultipliers.parse(List.of("nexo:gem=3", "Paper#1f=9"), w -> {});
        assertThat(m.multiplierFor(Set.of("nexo:gem"), "Paper#1f", "PAPER")).isEqualByComparingTo("3");
    }

    @Test
    void unlistedItemsUseOne() {
        assertThat(sample().multiplierFor(Set.of(), "Stone", "STONE")).isEqualByComparingTo("1");
        assertThat(sample().multiplierFor(Set.of(), null, null)).isEqualByComparingTo("1");
        assertThat(sample().multiplierFor(Set.of(), "#odd", null)).isEqualByComparingTo("1");
    }

    @Test
    void appliesTheMultiplierCappedAtOneHundredPercent() {
        assertThat(ItemTaxMultipliers.apply(1.0, d("2"))).isEqualTo(2.0);
        assertThat(ItemTaxMultipliers.apply(1.0, d("0.5"))).isEqualTo(0.5);
        assertThat(ItemTaxMultipliers.apply(1.0, BigDecimal.ZERO)).isEqualTo(0.0);
        assertThat(ItemTaxMultipliers.apply(60.0, d("2"))).isEqualTo(100.0);
        assertThat(ItemTaxMultipliers.apply(0.0, d("2"))).isEqualTo(0.0);
        assertThat(ItemTaxMultipliers.apply(7.0, BigDecimal.ONE)).isEqualTo(7.0);
    }
}
