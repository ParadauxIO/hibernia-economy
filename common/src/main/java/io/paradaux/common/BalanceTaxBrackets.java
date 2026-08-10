package io.paradaux.common;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Framework-free model of the flat-bracket balance tax shared by the Treasury
 * (personal) and Business (corporate) plugins (ADT-186).
 *
 * <p>Before this class the bracket defaults, the config-section parsing with its
 * {@code [0,1]} rate validation, and the {@code floorEntry} rate lookup were
 * copy-pasted into both {@code BalanceTaxConfiguration} classes and had begun to
 * drift in style. The only genuinely plugin-specific part is reading the Bukkit
 * {@code ConfigurationSection} into a {@code Map<String,String>} and the logger
 * flavour; everything below is pure JVM and lives here.
 *
 * <p>A bracket model is an immutable snapshot: {@code reload()} on either plugin
 * builds a fresh instance and swaps it in, never mutating one in place.
 */
public final class BalanceTaxBrackets {

    /** Default brackets used when config supplies none. Key = balance floor, value = weekly rate. */
    public static final NavigableMap<BigDecimal, BigDecimal> DEFAULTS;

    static {
        NavigableMap<BigDecimal, BigDecimal> m = new TreeMap<>();
        m.put(new BigDecimal("0.00"),       BigDecimal.ZERO);
        m.put(new BigDecimal("100000.00"),  new BigDecimal("0.01"));
        m.put(new BigDecimal("200000.00"),  new BigDecimal("0.012"));
        m.put(new BigDecimal("300000.00"),  new BigDecimal("0.014"));
        m.put(new BigDecimal("500000.00"),  new BigDecimal("0.018"));
        DEFAULTS = Collections.unmodifiableNavigableMap(m);
    }

    /** Sorted ascending by balance floor; always an unmodifiable snapshot. */
    private final NavigableMap<BigDecimal, BigDecimal> brackets;

    private BalanceTaxBrackets(NavigableMap<BigDecimal, BigDecimal> brackets) {
        this.brackets = Collections.unmodifiableNavigableMap(new TreeMap<>(brackets));
    }

    /**
     * Builds a bracket model from raw {@code floor -> rate} string entries (e.g. the
     * keys/values of a Bukkit {@code ConfigurationSection}). Entries whose key is not
     * a number, whose rate is missing/blank or not a number, or whose rate falls
     * outside {@code [0,1]}, are passed to {@code onWarn} (naming the offending field)
     * and skipped. If no valid entry remains the {@link #DEFAULTS} are used.
     *
     * @param rawEntries floor (as a decimal string) to weekly rate (as a decimal string)
     * @param onWarn     receives a fully-formatted, human-readable warning per skipped
     *                   entry; must not be {@code null}
     * @throws NullPointerException if {@code onWarn} is {@code null}
     */
    public static BalanceTaxBrackets fromRawEntries(Map<String, String> rawEntries, Consumer<String> onWarn) {
        Objects.requireNonNull(onWarn, "onWarn");
        NavigableMap<BigDecimal, BigDecimal> map = new TreeMap<>();
        if (rawEntries != null) {
            for (Map.Entry<String, String> e : rawEntries.entrySet()) {
                String key = e.getKey();
                BigDecimal min;
                try {
                    min = new BigDecimal(key);
                } catch (NumberFormatException ex) {
                    onWarn.accept("Invalid balance tax bracket key '" + key + "' — skipping");
                    continue;
                }

                String rawRate = e.getValue();
                if (rawRate == null || rawRate.isBlank()) {
                    onWarn.accept("Missing balance tax bracket rate for floor '" + key + "' — skipping");
                    continue;
                }
                BigDecimal rate;
                try {
                    rate = new BigDecimal(rawRate.trim());
                } catch (NumberFormatException ex) {
                    onWarn.accept("Invalid balance tax bracket rate for floor '" + key
                            + "': '" + rawRate + "' — skipping");
                    continue;
                }
                if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
                    onWarn.accept("Balance tax bracket rate for floor '" + key
                            + "' is out of range [0,1]: " + rate + " — skipping");
                    continue;
                }
                map.put(min, rate);
            }
        }
        if (map.isEmpty()) {
            map.putAll(DEFAULTS);
        }
        return new BalanceTaxBrackets(map);
    }

    /** Builds a model directly from already-parsed brackets (tests / programmatic use). */
    public static BalanceTaxBrackets of(NavigableMap<BigDecimal, BigDecimal> brackets) {
        return new BalanceTaxBrackets(brackets);
    }

    /**
     * Returns the weekly tax rate for {@code balance} under the flat-bracket model:
     * the whole balance is taxed at the rate of the bracket it falls into (not
     * marginal/progressive).
     *
     * @return the bracket's weekly rate, or {@link BigDecimal#ZERO} if none matches
     */
    public BigDecimal weeklyRate(BigDecimal balance) {
        Map.Entry<BigDecimal, BigDecimal> entry = brackets.floorEntry(balance);
        return entry != null ? entry.getValue() : BigDecimal.ZERO;
    }

    /** Number of brackets in this snapshot. */
    public int size() {
        return brackets.size();
    }
}
