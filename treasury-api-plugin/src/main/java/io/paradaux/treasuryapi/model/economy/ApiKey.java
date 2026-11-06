package io.paradaux.treasuryapi.model.economy;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class ApiKey {
    private int keyId;
    /** Persisted API-key scope. */
    private KeyType keyType;
    /** Null for firm-wide BUSINESS keys; set for keys restricted to one account. */
    private Integer accountId;
    /** Null for PERSONAL and GOVERNMENT keys. */
    private Integer firmId;

    private UUID ownerUuid;
    private String jwtId; // jti claim
    private String token; // full signed JWT
    private Instant issuedAt;
    private Instant expiresAt;
    private boolean revoked;
}
