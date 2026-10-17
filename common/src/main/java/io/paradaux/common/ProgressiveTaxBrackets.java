package io.paradaux.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Framework-free model of a marginal (progressive) tax schedule, used by the
 * Treasury progressive income tax.
 *
 * <p>Unlike {@link BalanceTaxBrackets}, which taxes the whole base at the rate of
 * the bracket it falls into, each rate here applies only to the slice of income
 * between its floor and the next bracket's floor. With brackets
 * {@code 0 -> 0%, 5000 -> 3%}, cumulative income of $5,500 owes 3% of the $500
 * above $5,000.
 *
 * <p>Income tax is collected on every payment rather than once per period, so the
 * main entry point is {@link #taxOnIncrement}: the tax owed by one payment is the
 * difference between the (rounded) tax on cumulative income after and before it.
 * Rounding the cumulative totals rather than each slice means the per-payment
 * amounts always sum to exactly the tax on the period's total income.
 *
 * <p>A bracket model is an immutable snapshot; a reload builds a fresh instance.
 */
public final class ProgressiveTaxBrackets {

    /** Money scale used by the ledger ({@code DECIMAL(19,2)}). */
    private static final int MONEY_SCALE = 2;

    private static final ProgressiveTaxBrackets NONE = new ProgressiveTaxBrackets(new TreeMap<>());

    /** Sorted ascending by income floor; always an unmodifiable snapshot. */
    private final NavigableMap<BigDecimal, BigDecimal> brackets;

    private ProgressiveTaxBrackets(NavigableMap<BigDecimal, BigDecimal> brackets) {
        this.brackets = Collections.unmodifiableNavigableMap(new TreeMap<>(brackets));
    }

    /**
     * Builds a bracket model from raw {@code floor -> rate} string entries (e.g. the
     * keys/values of a Bukkit {@code ConfigurationSection}). Entries whose floor is
     * not a non-negative number, or whose rate is missing, not a number, or outside
     * {@code [0,1]}, are passed to {@code onWarn} and skipped. If no valid entry
     * remains the schedule taxes nothing.
     *
     * @param rawEntries income floor (as a decimal string) to marginal rate (as a decimal string)
     * @param onWarn     receives a human-readable warning per skipped entry; must not be {@code null}
     * @throws NullPointerException if {@code onWarn} is {@code null}
     */
    public static ProgressiveTaxBrackets fromRawEntries(Map<String, String> rawEntries, Consumer<String> onWarn) {
        Objects.requireNonNull(onWarn, "onWarn");
        NavigableMap<BigDecimal, BigDecimal> map = new TreeMap<>();
        if (rawEntries != null) {
            for (Map.Entry<String, String> e : rawEntries.entrySet()) {
                String key = e.getKey();
                BigDecimal floor;
                try {
                    floor = new BigDecimal(key.trim());
                } catch (NumberFormatException ex) {
                    onWarn.accept("Invalid income tax bracket floor '" + key + "' — skipping");
                    continue;
                }
                if (floor.signum() < 0) {
                    onWarn.accept("Income tax bracket floor '" + key + "' is negative — skipping");
                    continue;
                }

                String rawRate = e.getValue();
                if (rawRate == null || rawRate.isBlank()) {
                    onWarn.accept("Missing income tax bracket rate for floor '" + key + "' — skipping");
                    continue;
                }
                BigDecimal rate;
                try {
                    rate = new BigDecimal(rawRate.trim());
                } catch (NumberFormatException ex) {
                    onWarn.accept(
                            "Invalid income tax bracket rate for floor '" + key + "': '" + rawRate + "' — skipping");
                    continue;
                }
                if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                    onWarn.accept("Income tax bracket rate for floor '" + key + "' is out of range [0,1]: " + rate
                            + " — skipping");
                    continue;
                }
                map.put(floor, rate);
            }
        }
        return map.isEmpty() ? NONE : new ProgressiveTaxBrackets(map);
    }

    /** Builds a model directly from already-parsed brackets (tests / programmatic use). */
    public static ProgressiveTaxBrackets of(NavigableMap<BigDecimal, BigDecimal> brackets) {
        return new ProgressiveTaxBrackets(brackets);
    }

    /**
     * The exact (unrounded) marginal tax owed on {@code income}: each bracket's rate
     * applied to the part of the income between its floor and the next floor.
     * Income below the lowest floor is untaxed.
     */
    public BigDecimal cumulativeTax(BigDecimal income) {
        if (income == null || income.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal tax = BigDecimal.ZERO;
        Iterator<Map.Entry<BigDecimal, BigDecimal>> it = brackets.entrySet().iterator();
        Map.Entry<BigDecimal, BigDecimal> current = it.hasNext() ? it.next() : null;
        while (current != null && income.compareTo(current.getKey()) > 0) {
            Map.Entry<BigDecimal, BigDecimal> next = it.hasNext() ? it.next() : null;
            BigDecimal ceiling = next == null ? income : next.getKey().min(income);
            tax = tax.add(ceiling.subtract(current.getKey()).multiply(current.getValue()));
            current = next;
        }
        return tax;
    }

    /**
     * Tax owed by a payment of {@code amount} that raises cumulative period income
     * from {@code incomeBefore} to {@code incomeBefore + amount}. Computed as the
     * difference of the cumulative totals, each rounded half-up to cents, so a
     * period's per-payment taxes always add up to the tax on its total income.
     * Never negative and never more than {@code amount}.
     *
     * @return the tax for this payment, at money scale
     */
    public BigDecimal taxOnIncrement(BigDecimal incomeBefore, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE);
        }
        BigDecimal before = incomeBefore == null || incomeBefore.signum() < 0 ? BigDecimal.ZERO : incomeBefore;
        BigDecimal taxBefore = cumulativeTax(before).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal taxAfter = cumulativeTax(before.add(amount)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal tax = taxAfter.subtract(taxBefore).max(BigDecimal.ZERO);
        return tax.min(amount.setScale(MONEY_SCALE, RoundingMode.DOWN));
    }

    /** The brackets as income floor to marginal rate, ascending. Unmodifiable. */
    public NavigableMap<BigDecimal, BigDecimal> asMap() {
        return brackets;
    }

    /** {@code true} when no bracket has a positive rate, i.e. nothing is ever taxed. */
    public boolean isEmpty() {
        return brackets.values().stream().noneMatch(rate -> rate.signum() > 0);
    }

    /** Number of brackets in this snapshot. */
    public int size() {
        return brackets.size();
    }
}
