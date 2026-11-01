package com.minecraftcitiesnetwork.families.patch;

import java.util.*;

/** Validates persisted background values before packet resource construction. */
public final class SafeBackground {
    private static final Set<String> TABS = Set.of("stone", "nether", "end", "adventure", "husbandry");
    private record Settings(Set<String> allowed, String fallback) {}
    private static volatile Settings settings = new Settings(Set.of("moss_block"), "block/moss_block");

    public static void configure(List<String> choices, String fallback) {
        Set<String> allowed = new HashSet<>();
        if (choices != null) for (String choice : choices) {
            String value = normalize(choice);
            if (value != null) allowed.add(value);
        }
        String value = normalize(fallback);
        if (value != null) allowed.add(value);
        settings = new Settings(Set.copyOf(allowed), value == null ? "block/moss_block" : expand(value));
    }

    public static String backgroundId(String input) {
        Settings current = settings;
        String value = normalize(input);
        if (value == null) return current.fallback();
        // A path explicitly selects a resource; bare values select configured choices.
        if (value.indexOf('/') >= 0 || TABS.contains(value) || current.allowed().contains(value))
            return expand(value);
        return current.fallback();
    }

    private static String normalize(String input) {
        if (input == null) return null;
        String value = input.trim();
        if (value.startsWith("minecraft:")) value = value.substring(10);
        if (value.startsWith("textures/")) value = value.substring(9);
        if (value.endsWith(".png")) value = value.substring(0, value.length() - 4);
        // Preserve the pre-existing case-insensitive vanilla tab aliases only.
        if (TABS.contains(value.toLowerCase(Locale.ROOT))) value = value.toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*")) return null;
        for (String segment : value.split("/")) if (segment.equals(".") || segment.equals("..")) return null;
        return value;
    }

    private static String expand(String value) {
        if (value.indexOf('/') >= 0) return value;
        return TABS.contains(value) ? "gui/advancements/backgrounds/" + value : "block/" + value;
    }
}
