package io.paradaux.chestshop.model.tax;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * Balance-based sales tax brackets ({@code TAX_BRACKETS}).
 *
 * <p>When configured they replace {@code TAX_AMOUNT} for player shops: the balance of the
 * party receiving the money, before the sale, picks a bracket and that bracket's percent
 * applies to the whole sale. Brackets are flat, not marginal, like Treasury's balance tax:
 * with {@code 0=0, 1000=1, 5000=2} a seller holding $4,000 pays 1% of every sale and one
 * holding $6,000 pays 2%. A balance below the lowest floor pays nothing.
 *
 * <p>An immutable snapshot; a config reload builds a fresh one.
 */
public final class SalesTaxBrackets {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final SalesTaxBrackets NONE = new SalesTaxBrackets(new TreeMap<>());

    /** Balance floor to percent, ascending. */
    private final NavigableMap<BigDecimal, BigDecimal> brackets;

    private SalesTaxBrackets(NavigableMap<BigDecimal, BigDecimal> brackets) {
        this.brackets = Collections.unmodifiableNavigableMap(brackets);
    }

    /**
     * Parses {@code "<balance floor>=<percent>"} entries. Entries that are malformed, have a
     * negative floor, or a percent outside {@code [0, 100]} are reported to {@code onWarn}
     * and skipped.
     */
    public static SalesTaxBrackets parse(@Nullable List<String> entries, Consumer<String> onWarn) {
        NavigableMap<BigDecimal, BigDecimal> brackets = new TreeMap<>();
        if (entries != null) {
            for (String entry : entries) {
                if (entry == null) {
                    continue;
                }
                int separator = entry.indexOf('=');
                if (separator < 0) {
                    onWarn.accept(
                            "Tax bracket '" + entry + "' is not in the form <balance floor>=<percent> - skipping");
                    continue;
                }
                BigDecimal floor;
                BigDecimal percent;
                try {
                    floor = new BigDecimal(entry.substring(0, separator).trim());
                    percent = new BigDecimal(entry.substring(separator + 1).trim());
                } catch (NumberFormatException e) {
                    onWarn.accept("Tax bracket '" + entry + "' does not contain two numbers - skipping");
                    continue;
                }
                if (floor.signum() < 0) {
                    onWarn.accept("Tax bracket '" + entry + "' has a negative balance floor - skipping");
                    continue;
                }
                if (percent.signum() < 0 || percent.compareTo(ONE_HUNDRED) > 0) {
                    onWarn.accept("Tax bracket '" + entry + "' has a percent outside 0-100 - skipping");
                    continue;
                }
                brackets.put(floor, percent);
            }
        }
        return brackets.isEmpty() ? NONE : new SalesTaxBrackets(brackets);
    }

    /** {@code true} when no bracket is configured, i.e. {@code TAX_AMOUNT} applies. */
    public boolean isEmpty() {
        return brackets.isEmpty();
    }

    /**
     * The percent for a balance: the bracket with the highest floor not above it, or 0 when
     * the balance is below every floor. A {@code null} balance is treated as zero.
     */
    public BigDecimal percentFor(@Nullable BigDecimal balance) {
        Map.Entry<BigDecimal, BigDecimal> bracket = brackets.floorEntry(balance != null ? balance : BigDecimal.ZERO);
        return bracket != null ? bracket.getValue() : BigDecimal.ZERO;
    }

    /** Balance floor to percent, ascending. Unmodifiable. */
    public NavigableMap<BigDecimal, BigDecimal> asMap() {
        return brackets;
    }
}
