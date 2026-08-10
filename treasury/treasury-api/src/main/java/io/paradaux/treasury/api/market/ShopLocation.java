package io.paradaux.treasury.api.market;

import java.util.UUID;

/**
 * The bare location of a known shop sign — what a resync enumeration needs to
 * decide which chunks to scan and which registry rows have no live sign anymore
 * (PAR-169/174). Lighter than {@link ShopResult}: no item, owner or pricing.
 */
public record ShopLocation(
        String world,
        UUID worldUuid,   // null for rows written before V25
        int signX, int signY, int signZ
) {}
