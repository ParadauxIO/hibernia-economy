package io.paradaux.treasury.services;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Computes and collects the prorated personal balance tax owed since a player's
 * previous login.
 *
 * <p>Tax formula:
 * <pre>
 *   proration   = seconds_since_last_login / (7 × 24 × 3600)
 *   weekly_rate = bracket_rate(balance)            // flat bracket, not marginal
 *   tax         = balance × weekly_rate × proration
 * </pre>
 *
 * <p>This service does not own the login clock: {@link io.paradaux.treasury.services.PlayerDirectoryService}
 * atomically records the new login and hands back the previous epoch, which is
 * passed in here as {@code previousLoginEpoch}. Because the directory advances
 * the clock <em>before</em> collection, a crash mid-collection drops that one
 * period rather than double-charging on the next login. The dedup key on the
 * {@link io.paradaux.treasury.model.tax.TaxCollection} prevents a second charge if the same login timestamp is
 * seen again (e.g. from a retry).
 */
public interface BalanceTaxService {

    /**
     * Collects any personal balance tax owed for the period between a player's
     * previous login and now. The login clock is advanced by the player directory,
     * which supplies {@code previousLoginEpoch}; this method only reads balance and
     * collects — it never writes login times.
     *
     * @param playerUuid          the joining player's UUID
     * @param previousLoginEpoch  the player's previous login epoch (seconds), or
     *                            {@code null} if this is their first ever login
     * @param loginEpochSecs      the current login time as Unix epoch seconds
     */
    void collect(@NotNull UUID playerUuid, Long previousLoginEpoch, long loginEpochSecs);
}
