package io.paradaux.business.services;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory, short-TTL cache of firm display names for command tab-completion, so
 * suggestions don't hit the DB on every keystroke. Two query angles back the two
 * resolver forms (PAR-13):
 *
 * <ul>
 *   <li>{@link #playerFirmNames(UUID)} — firms the player owns or is employed by.</li>
 *   <li>{@link #activeFirmNames()} — every active firm (the broad pool).</li>
 * </ul>
 *
 * The online-firm form is composed in the resolver by unioning {@code playerFirmNames}
 * over the currently online players, keeping the Bukkit dependency out of here so the
 * cache stays unit-testable. Entries refresh lazily once older than the TTL.
 */
public interface FirmSuggestionCache {

    /** Names of firms the player owns or is employed by (cached per-player). */
    List<String> playerFirmNames(UUID playerId);

    /** Every active firm name (the broad pool, for unknown-firm suggestions). */
    Set<String> activeFirmNames();
}
