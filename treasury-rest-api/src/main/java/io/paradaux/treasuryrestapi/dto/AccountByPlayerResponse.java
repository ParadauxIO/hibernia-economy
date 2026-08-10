package io.paradaux.treasuryrestapi.dto;

/**
 * Response body for GET /api/v1/accounts/by-player.
 *
 * <p>{@code playerName} is the last-known IGN from the {@code economy_players}
 * cache and may be null if the resolved player hasn't been seen on the server
 * yet (only possible when the caller queries by UUID).
 */
public record AccountByPlayerResponse(long accountId, String playerUuid, String playerName) {}
