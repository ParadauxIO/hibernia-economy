package io.paradaux.treasury.utils;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public final class CallingPluginDetector {

    private static final Set<String> IGNORE_PACKAGES = Set.of(
            "java.", "jdk.", "sun.",
            "org.bukkit.", "net.md_5.",
            "io.papermc.",
            // Only skip our own plugin, not every first-party io.paradaux.*
            // caller — a broad "io.paradaux." prefix would mis-attribute
            // source-income tax and plugin-system accounts for sibling plugins
            // (business, realty, …) that legitimately move money through us.
            "io.paradaux.treasury."
    );

    private static final ConcurrentHashMap<Class<?>, Plugin> PROVIDER_CACHE = new ConcurrentHashMap<>();

    private CallingPluginDetector() {}

    public static Optional<Plugin> current() {
        // Keep class refs so we can ask Bukkit for the provider.
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk((Stream<StackWalker.StackFrame> s) -> s
                        .map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(cls -> !shouldIgnore(cls))
                        .map(CallingPluginDetector::resolveProvider)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .findFirst()
                );
    }

    private static boolean shouldIgnore(Class<?> cls) {
        String n = cls.getName();
        for (String p : IGNORE_PACKAGES) {
            if (n.startsWith(p)) return true;
        }
        return false;
    }

    private static Optional<Plugin> resolveProvider(Class<?> cls) {
        Plugin cached = PROVIDER_CACHE.get(cls);
        if (cached != null) {
            // If plugin got disabled, fall through to re-resolve once.
            if (cached.isEnabled()) return Optional.of(cached);
        }
        try {
            Plugin p = JavaPlugin.getProvidingPlugin(cls);
            if (p != null && p.isEnabled()) {
                PROVIDER_CACHE.put(cls, p);
                return Optional.of(p);
            }
        } catch (IllegalArgumentException ignored) {
            // Not provided by a plugin — carry on.
        }
        return Optional.empty();
    }

    public static String currentPluginKeyOrDefault(String fallback) {
        return current().map(Plugin::getName).orElse(fallback);
    }

    public static Plugin requireCurrentPlugin() {
        return current().orElseThrow(() ->
                new IllegalStateException("Cannot determine calling plugin from stack"));
    }
}
