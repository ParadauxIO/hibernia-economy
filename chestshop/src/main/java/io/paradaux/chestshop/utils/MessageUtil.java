package io.paradaux.chestshop.utils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure helper for assembling the placeholder-value map passed to the framework
 * {@link io.paradaux.hibernia.framework.i18n.Message}. Every ChestShop template begins with
 * {@code {prefix}}, so {@code withPrefix=false} blanks it for continuation lines. Relocated
 * off the ChestShop main class (PAR-300) — it's a pure function, not plugin state.
 */
public final class MessageUtil {

    private MessageUtil() {
    }

    public static Map<String, Object> values(boolean withPrefix, Map<String, String> base, String... replacements) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (!withPrefix) {
            values.put("prefix", "");
        }
        if (base != null) {
            values.putAll(base);
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            values.put(replacements[i], replacements[i + 1]);
        }
        return values;
    }
}
