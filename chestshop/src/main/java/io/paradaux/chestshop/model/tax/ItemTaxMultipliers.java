package io.paradaux.chestshop.model.tax;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * Per-item sales tax multipliers ({@code TAX_ITEM_MULTIPLIERS}).
 *
 * <p>Each entry is {@code "<item>=<multiplier>"}; a sale's tax percent is multiplied by the
 * traded item's multiplier and capped at 100%. An item is matched, most specific first, by:
 * <ol>
 *   <li>a custom item id, {@code <namespace>:<id>} - the item's persistent data holds the
 *       string {@code <id>} under that namespace, which is how Nexo ({@code nexo:id}) and
 *       most custom item plugins tag their items;</li>
 *   <li>ChestShop's item code (as written on signs), with or without its {@code #}
 *       metadata suffix;</li>
 *   <li>the material name - only for items without a custom id, so a custom item made
 *       from paper is not taxed as paper.</li>
 * </ol>
 * Items with no entry use a multiplier of 1. An immutable snapshot.
 */
public final class ItemTaxMultipliers {

    private static final double MAX_PERCENT = 100;
    private static final ItemTaxMultipliers NONE = new ItemTaxMultipliers(new HashMap<>());

    /** Lower-cased item name to multiplier. */
    private final Map<String, BigDecimal> multipliers;

    private ItemTaxMultipliers(Map<String, BigDecimal> multipliers) {
        this.multipliers = Collections.unmodifiableMap(multipliers);
    }

    /**
     * Parses {@code "<item>=<multiplier>"} entries, matching item names case-insensitively.
     * Malformed entries and negative multipliers are reported to {@code onWarn} and skipped.
     */
    public static ItemTaxMultipliers parse(@Nullable List<String> entries, Consumer<String> onWarn) {
        Map<String, BigDecimal> multipliers = new HashMap<>();
        if (entries != null) {
            for (String entry : entries) {
                if (entry == null) {
                    continue;
                }
                int separator = entry.lastIndexOf('=');
                String item = separator > 0 ? entry.substring(0, separator).trim() : "";
                if (item.isEmpty()) {
                    onWarn.accept(
                            "Tax item multiplier '" + entry + "' is not in the form <item>=<multiplier> - skipping");
                    continue;
                }
                BigDecimal multiplier;
                try {
                    multiplier = new BigDecimal(entry.substring(separator + 1).trim());
                } catch (NumberFormatException e) {
                    onWarn.accept("Tax item multiplier '" + entry + "' has no valid multiplier - skipping");
                    continue;
                }
                if (multiplier.signum() < 0) {
                    onWarn.accept("Tax item multiplier '" + entry + "' is negative - skipping");
                    continue;
                }
                multipliers.put(item.toLowerCase(Locale.ROOT), multiplier);
            }
        }
        return multipliers.isEmpty() ? NONE : new ItemTaxMultipliers(multipliers);
    }

    /** {@code true} when no multiplier is configured. */
    public boolean isEmpty() {
        return multipliers.isEmpty();
    }

    /**
     * The multiplier for one item.
     *
     * <p>An item with custom ids is matched only by them or by its exact item code: its code
     * can be its base material plus metadata (a Nexo item without a resolver reads
     * {@code Paper#2b}), so falling back further would tax it as that material.
     *
     * @param customIds the item's custom ids as {@code namespace:id} (any case); empty for a plain item
     * @param itemCode  ChestShop's item code for it, or {@code null}
     * @param material  its material name, or {@code null}
     */
    public BigDecimal multiplierFor(
            Collection<String> customIds, @Nullable String itemCode, @Nullable String material) {
        for (String id : customIds) {
            BigDecimal m = multipliers.get(id.toLowerCase(Locale.ROOT));
            if (m != null) {
                return m;
            }
        }
        boolean hasCustomIds = !customIds.isEmpty();
        if (itemCode != null) {
            String code = itemCode.toLowerCase(Locale.ROOT);
            BigDecimal m = multipliers.get(code);
            int hash = code.indexOf('#');
            if (m == null && hash > 0 && !hasCustomIds) {
                m = multipliers.get(code.substring(0, hash));
            }
            if (m != null) {
                return m;
            }
        }
        if (!hasCustomIds && material != null) {
            BigDecimal m = multipliers.get(material.toLowerCase(Locale.ROOT));
            if (m != null) {
                return m;
            }
        }
        return BigDecimal.ONE;
    }

    /** Applies {@code multiplier} to a tax percent, capped at 100. */
    public static double apply(double percent, BigDecimal multiplier) {
        if (percent == 0 || multiplier.compareTo(BigDecimal.ONE) == 0) {
            return percent;
        }
        return Math.min(
                MAX_PERCENT, BigDecimal.valueOf(percent).multiply(multiplier).doubleValue());
    }
}
