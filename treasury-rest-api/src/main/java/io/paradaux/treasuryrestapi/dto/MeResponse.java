package io.paradaux.treasuryrestapi.dto;

import io.paradaux.treasuryrestapi.security.VerifiedToken;
import java.util.UUID;

/**
 * Response for {@code GET /api/v1/auth/me} — the calling key's own identity and
 * scope, derived straight from the authenticated {@link VerifiedToken}.
 *
 * <ul>
 *   <li>PERSONAL / GOVERNMENT keys: {@code accountId} is set, {@code firmId} is null.</li>
 *   <li>BUSINESS keys: {@code firmId} is set; optional {@code accountId} restricts access to one account.</li>
 * </ul>
 */
public record MeResponse(long keyId, UUID ownerUuid, String keyType, Long accountId, Long firmId) {

    public static MeResponse from(VerifiedToken token) {
        return new MeResponse(token.keyId(), token.ownerUuid(), token.keyType(), token.accountId(), token.firmId());
    }
}
