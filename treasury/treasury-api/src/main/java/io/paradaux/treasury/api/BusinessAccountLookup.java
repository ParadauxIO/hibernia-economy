package io.paradaux.treasury.api;

/**
 * Optional Bukkit service supplied by Business to resolve a live firm's default account.
 * Treasury uses this before legacy account-display-name lookup, so released firm names
 * cannot route payments to retained accounts of an archived firm.
 */
@FunctionalInterface
public interface BusinessAccountLookup {
    /**
     * Resolve a live firm by name, returning its default Treasury account ID.
     * @param name exact firm display name, matched according to Business name rules
     * @return default account ID, or null when no active firm with that name exists
     */
    Integer findDefaultAccount(String name);
}
