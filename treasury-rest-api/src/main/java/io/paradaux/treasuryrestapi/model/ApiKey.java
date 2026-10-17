package io.paradaux.treasuryrestapi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKey {
    private long keyId;
    /** "PERSONAL" | "BUSINESS" | "GOVERNMENT" */
    private String keyType;
    /** Creator/issuer — api_keys.owner_uuid_bin (the human who minted the key). */
    private UUID ownerUuid;
    /** null for BUSINESS keys (those are scoped to a firm, not an account). */
    private Integer accountId;
    /** null for PERSONAL / GOVERNMENT keys. */
    private Integer firmId;
    /** UUID string stored in api_keys.jwt_id — compared against the jti claim. */
    private String jwtId;
    private boolean revoked;
    // No token field: the signed JWT is no longer persisted (ADT-6). Verification
    // needs only jwt_id by kid; a freshly-minted token is returned to the caller
    // at rotation time and never stored or read back.
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
}
