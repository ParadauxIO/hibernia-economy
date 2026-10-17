package io.paradaux.treasury.services;

import java.util.Optional;
import java.util.UUID;

/**
 * The unified player directory: a deterministic, DB-backed UUID ↔ name map that
 * does not depend on Bukkit usercache state. Treasury owns the writer
 * ({@code economy_players}); commands resolve a typed name to a UUID through this
 * even when the player isn't currently cached (PAR-35 — the foundation for the
 * Bedrock/uncached-name resolution bugs PAR-150 / PAR-149).
 */
public interface PlayerDirectoryService {

    /**
     * Records (upserts) a player's current name and last-login epoch on login,
     * and returns the player's <em>previous</em> last-login epoch (the value
     * before this call), or {@code null} if this is their first ever login.
     *
     * <p>This is the single owner of the {@code economy_players} row write and
     * the single atomic read-old/write-new of {@code last_login_epoch}, so
     * callers that prorate over the elapsed period (balance tax) consume the
     * returned value rather than reading the column themselves — which would
     * race this write. Serialised per player so concurrent logins can't both
     * observe the same previous value.
     */
    Long recordLogin(UUID playerUuid, String currentName, long epochSeconds);

    /**
     * Resolves a current name to a UUID, case-insensitively, regardless of
     * usercache state. Bedrock/Floodgate names (leading {@code .}) resolve
     * verbatim. Empty for a blank input or an unknown name.
     */
    Optional<UUID> resolveUuidByName(String name);

    /** The player's last-known name, or empty if not in the directory. */
    Optional<String> resolveNameByUuid(UUID playerUuid);
}
