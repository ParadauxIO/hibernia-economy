package io.paradaux.chestshop.model.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SalesTaxBracketsTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static SalesTaxBrackets sample() {
        return SalesTaxBrackets.parse(List.of("0=0", "1000=1", "5000=2"), w -> {});
    }

    @Test
    void parsesFloorPercentEntries() {
        List<String> warnings = new ArrayList<>();
        SalesTaxBrackets b = SalesTaxBrackets.parse(List.of("0=0", " 1000 = 1 ", "5000=2.5"), warnings::add);

        assertThat(b.isEmpty()).isFalse();
        assertThat(b.asMap()).hasSize(3).containsEntry(d("5000"), d("2.5"));
        assertThat(warnings).isEmpty();
    }

    @Test
    void skipsInvalidEntriesWithAWarningEach() {
        List<String> warnings = new ArrayList<>();
        SalesTaxBrackets b = SalesTaxBrackets.parse(
                Arrays.asList("1000", "a=1", "-5=1", "10=-1", "20=101", null, "30=100"), warnings::add);

        assertThat(b.asMap()).containsOnlyKeys(d("30"));
        assertThat(warnings).hasSize(5);
        assertThat(warnings.get(0)).contains("<balance floor>=<percent>");
        assertThat(warnings.get(1)).contains("two numbers");
        assertThat(warnings.get(2)).contains("negative");
        assertThat(warnings.get(3)).contains("0-100");
        assertThat(warnings.get(4)).contains("0-100");
    }

    @Test
    void emptyOrMissingConfigMeansNoBrackets() {
        assertThat(SalesTaxBrackets.parse(null, w -> {}).isEmpty()).isTrue();
        assertThat(SalesTaxBrackets.parse(List.of(), w -> {}).isEmpty()).isTrue();
        assertThat(SalesTaxBrackets.parse(List.of("bad"), w -> {}).isEmpty()).isTrue();
    }

    @Test
    void picksTheHighestFloorNotAboveTheBalance() {
        SalesTaxBrackets b = sample();
        assertThat(b.percentFor(d("999.99"))).isEqualByComparingTo("0");
        assertThat(b.percentFor(d("1000"))).isEqualByComparingTo("1");
        assertThat(b.percentFor(d("4999.99"))).isEqualByComparingTo("1");
        assertThat(b.percentFor(d("5000"))).isEqualByComparingTo("2");
        assertThat(b.percentFor(d("1000000"))).isEqualByComparingTo("2");
    }

    @Test
    void balancesBelowEveryFloorAreUntaxed() {
        SalesTaxBrackets b = SalesTaxBrackets.parse(List.of("1000=5"), w -> {});
        assertThat(b.percentFor(d("10"))).isEqualByComparingTo("0");
        assertThat(b.percentFor(d("-50"))).isEqualByComparingTo("0");
        assertThat(b.percentFor(null)).isEqualByComparingTo("0");
    }

    @Test
    void theBracketMapIsUnmodifiable() {
        assertThatThrownBy(() -> sample().asMap().put(d("1"), d("1")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
