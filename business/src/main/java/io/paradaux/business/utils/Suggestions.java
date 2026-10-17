package io.paradaux.business.utils;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Pure, stateless tab-completion helpers. Extracted off {@code FirmSuggestionCache} (a
 * @Singleton) so the prefix filter — a generic mechanism used by several command-parameter
 * resolvers — no longer lives as a static method on a stateful service (PAR-296).
 */
public final class Suggestions {

    private Suggestions() {
    }

    /**
     * Case-insensitive prefix match over {@code pool}: distinct, case-insensitively sorted,
     * capped at {@code limit}.
     */
    public static List<String> match(Collection<String> pool, String prefix, int limit) {
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return pool.stream()
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(p))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .limit(limit)
                .collect(Collectors.toList());
    }
}
