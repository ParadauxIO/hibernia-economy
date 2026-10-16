package io.paradaux.treasuryrestapi.dto;

/**
 * One row of GET /accounts/baltop.
 * {@code balance} is a decimal string to avoid IEEE 754 precision loss.
 */
public record BaltopEntry(long rank,
                          long accountId,
                          String playerUuid,
                          String playerName,
                          String balance) {}
