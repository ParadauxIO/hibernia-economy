package io.paradaux.treasuryrestapi.service;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.paradaux.treasuryrestapi.dto.RotateResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.mapper.ApiKeyMapper;
import io.paradaux.treasuryrestapi.model.ApiKey;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final long TOKEN_LIFETIME_DAYS = 180;

    private final ApiKeyMapper apiKeyMapper;
    private final SecretKey signingKey;

    public AuthService(ApiKeyMapper apiKeyMapper, SecretKey signingKey) {
        this.apiKeyMapper = apiKeyMapper;
        this.signingKey = signingKey;
    }

    /**
     * Rotates the token identified by the verified principal.
     * The old token is immediately invalidated because the jti stored in the DB is replaced.
     * Re-confirms revocation status under a row-level lock before issuing.
     */
    @Transactional
    public RotateResponse rotateToken(VerifiedToken verified) {
        log.debug("Beginning token rotation for keyId={}", verified.keyId());

        // Re-confirm revoked = 0 under a SELECT FOR UPDATE lock
        ApiKey locked = apiKeyMapper.findByKeyIdForUpdate(verified.keyId());
        if (locked == null || locked.isRevoked()) {
            log.warn("Token rotation rejected: keyId={} is revoked or missing", verified.keyId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "Token has been revoked.");
        }

        UUID newJti = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(TOKEN_LIFETIME_DAYS, ChronoUnit.DAYS);

        String newToken = buildJwt(verified, newJti, now, expiresAt);

        // The token is returned to the owner below, never persisted (ADT-6) — the
        // jti rotation is what invalidates the old token.
        int rows = apiKeyMapper.rotateKey(
                verified.keyId(),
                newJti.toString(),
                LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
        );
        if (rows != 1) {
            // The UPDATE has WHERE revoked = 0; a 0-row result means the key
            // was revoked between the FOR UPDATE check above and the UPDATE.
            log.warn("Token rotation no-op: keyId={} rotation UPDATE affected 0 rows " +
                    "(key likely revoked concurrently)", verified.keyId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED",
                    "Token has been revoked.");
        }

        log.info("Token rotated for keyId={}, newJti={}, expiresAt={}", verified.keyId(), newJti, expiresAt);
        return new RotateResponse(verified.keyId(), newToken, now, expiresAt);
    }

    /**
     * Admin force-rotation by key id. Invalidates the current token by replacing
     * its jti, so a leaked token stops working immediately. No usable token is
     * produced — it is neither returned (an admin must never see another user's
     * secret) nor persisted (ADT-6) — so the owner reissues in-game to get a new
     * one. Returns the new expiry for display.
     */
    @Transactional
    public Instant adminForceRotate(long keyId) {
        ApiKey locked = apiKeyMapper.findByKeyIdForUpdate(keyId);
        if (locked == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "KEY_NOT_FOUND", "API key not found.");
        }
        if (locked.isRevoked()) {
            throw new ApiException(HttpStatus.CONFLICT, "TOKEN_REVOKED",
                    "Key is revoked; revoked keys cannot be rotated.");
        }

        UUID newJti = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(TOKEN_LIFETIME_DAYS, ChronoUnit.DAYS);

        int rows = apiKeyMapper.rotateKey(keyId, newJti.toString(),
                LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        if (rows != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "ROTATE_FAILED",
                    "Rotation failed (key revoked concurrently).");
        }
        log.info("Admin force-rotated keyId={} newJti={} expiresAt={}", keyId, newJti, expiresAt);
        return expiresAt;
    }

    private String buildJwt(VerifiedToken verified, UUID jti, Instant issuedAt, Instant expiresAt) {
        JwtBuilder builder = Jwts.builder()
                .header()
                    .add("kid", String.valueOf(verified.keyId()))
                    .and()
                .subject(verified.ownerUuid().toString())
                .claim("type", verified.keyType())
                .id(jti.toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt));

        if (verified.accountId() != null) builder.claim("acc", verified.accountId());
        if (verified.firmId() != null)    builder.claim("firm", verified.firmId());

        // Pin HS256 explicitly. JJWT picks HS256 from the SecretKey's algorithm
        // today, but a future major version could change the default. The
        // verifier expects HS256 (matches the issuer in treasury-api-plugin).
        return builder.signWith(signingKey, Jwts.SIG.HS256).compact();
    }
}
