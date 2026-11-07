package io.paradaux.treasury.api;

/**
 * Database-wide release controls. Obtain from Bukkit's ServicesManager.
 * Missing provider/keys must be treated as disabled. Checks perform no I/O.
 * Guard service entry points and resolve optional services only after this check.
 * A flag does not grant permissions. Changes propagate asynchronously; admitted
 * operations finish normally and enabled snapshots expire after 30 seconds.
 */
@FunctionalInterface
public interface FeatureFlagApi {
    /** Safe fallback when Treasury's provider is unavailable. */
    FeatureFlagApi DISABLED = key -> false;

    /** Whether this namespaced feature is enabled in a fresh snapshot. */
    boolean isEnabled(String key);
}
