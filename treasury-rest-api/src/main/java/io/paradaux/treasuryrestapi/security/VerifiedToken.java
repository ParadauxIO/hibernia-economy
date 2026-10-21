package io.paradaux.treasuryrestapi.security;

import java.util.UUID;

/**
 * Populated after the JWT verification pipeline succeeds.
 * Passed through the SecurityContext as the principal.
 *
 * <ul>
 *   <li>PERSONAL / GOVERNMENT keys: {@code accountId} is set, {@code firmId} is null.</li>
 *   <li>BUSINESS keys: {@code firmId} is set; optional {@code accountId} restricts access to one account.</li>
 * </ul>
 */
public record VerifiedToken(long keyId, UUID ownerUuid, String keyType, Long accountId, Long firmId) {}
