package io.paradaux.treasuryapi.model.economy;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class ApiKey {
    private int keyId;
    /** PERSONAL | BUSINESS. (The schema enum still permits GOVERNMENT for
     *  forward-compat with the migration baseline, but the issuance surface
     *  has been removed — no command path produces a GOVERNMENT row.) */
    private KeyType keyType;
    /** Null for BUSINESS keys (which are firm-scoped, not account-scoped). */
    private Integer accountId;
    /** Null for PERSONAL keys. */
    private Integer firmId;
    private UUID ownerUuid;
    private String jwtId;      // jti claim
    private String token;      // full signed JWT
    private Instant issuedAt;
    private Instant expiresAt;
    private boolean revoked;
}
