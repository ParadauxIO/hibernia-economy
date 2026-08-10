package io.paradaux.treasuryrestapi.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.paradaux.treasuryrestapi.mapper.ApiKeyMapper;
import io.paradaux.treasuryrestapi.model.ApiKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.UUID;

/**
 * Verification pipeline for JWT-based API keys.
 *
 * <ol>
 *   <li>Bearer header present — checked by the filter before calling this</li>
 *   <li>JWT parseable and signature valid (HS256)</li>
 *   <li>exp in the future</li>
 *   <li>kid parses as a positive integer</li>
 *   <li>Row exists in api_keys for kid</li>
 *   <li>api_keys.revoked = 0</li>
 *   <li>api_keys.jwt_id = jti</li>
 *   <li>type claim matches DB key_type (PERSONAL / BUSINESS / GOVERNMENT)</li>
 *   <li>Scoped claim present: {@code acc} for PERSONAL/GOVERNMENT, {@code firm} for BUSINESS</li>
 * </ol>
 */
@Component
public class JwtTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenVerifier.class);

    private final SecretKey signingKey;
    private final ApiKeyMapper apiKeyMapper;

    public JwtTokenVerifier(SecretKey signingKey, ApiKeyMapper apiKeyMapper) {
        this.signingKey = signingKey;
        this.apiKeyMapper = apiKeyMapper;
    }

    public VerifiedToken verify(String token) {
        // Steps 2 & 3: parse, verify signature, check expiry
        Jws<Claims> jws;
        try {
            jws = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);
        } catch (ExpiredJwtException e) {
            throw new TokenException(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Token has expired.");
        } catch (JwtException e) {
            log.warn("JWT verification failed: [{}] {}", e.getClass().getSimpleName(), e.getMessage());
            throw new TokenException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Token signature or format is invalid.");
        }

        Claims claims = jws.getPayload();

        // Step 4: kid parses as a positive integer
        String kidStr = jws.getHeader().getKeyId();
        long keyId;
        try {
            keyId = Long.parseLong(kidStr);
            if (keyId <= 0) throw new NumberFormatException("non-positive");
        } catch (NumberFormatException e) {
            throw new TokenException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Token key ID is invalid.");
        }

        // Steps 5–7: single indexed DB read
        ApiKey apiKey = apiKeyMapper.findByKeyId(keyId);

        // Step 5: row must exist
        if (apiKey == null) {
            throw new TokenException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Token key not found.");
        }

        // Step 6: revocation check (fast-path before jti check)
        if (apiKey.isRevoked()) {
            throw new TokenException(HttpStatus.UNAUTHORIZED, "TOKEN_REVOKED", "Token has been revoked.");
        }

        // Step 7: jti freshness — guards against old tokens after a reissue
        String jti = claims.getId();
        if (!apiKey.getJwtId().equals(jti)) {
            throw new TokenException(HttpStatus.UNAUTHORIZED, "TOKEN_SUPERSEDED", "Token has been superseded by a newer issue.");
        }

        // Step 8: type claim must be present and match the DB record (defence in depth)
        String claimedType = claims.get("type", String.class);
        if (claimedType == null || !claimedType.equals(apiKey.getKeyType())) {
            log.warn("Key type mismatch: JWT type={} DB keyType={} keyId={}", claimedType, apiKey.getKeyType(), keyId);
            throw new TokenException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Token key type is invalid.");
        }

        // Step 9: extract the subject (owner UUID) — must be present and parse.
        // A raw UUID.fromString on null/malformed throws unchecked, which would
        // bubble up as 500. Validate explicitly so malformed tokens are 401.
        String subjectStr = claims.getSubject();
        if (subjectStr == null || subjectStr.isBlank()) {
            throw new TokenException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                    "Token subject is missing.");
        }
        UUID ownerUuid;
        try {
            ownerUuid = UUID.fromString(subjectStr);
        } catch (IllegalArgumentException e) {
            throw new TokenException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                    "Token subject is not a valid UUID.");
        }
        Long accountId = null;
        Long firmId = null;

        switch (claimedType) {
            case "PERSONAL", "GOVERNMENT" -> {
                Object accClaim = claims.get("acc");
                if (accClaim == null) {
                    throw new TokenException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                            "Token is missing required 'acc' claim.");
                }
                if (!(accClaim instanceof Number n)) {
                    throw new TokenException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                            "Token claim 'acc' must be a number.");
                }
                accountId = n.longValue();
                // Defence in depth: the signed acc claim must agree with the DB row.
                // A token can only carry a forged acc with the signing secret, but
                // pinning it to api_keys.account_id means a key whose scope changed
                // (or a misissued token) can never operate on a stale account.
                if (apiKey.getAccountId() != null && apiKey.getAccountId().longValue() != accountId) {
                    log.warn("Account claim mismatch: JWT acc={} DB account_id={} keyId={}",
                            accountId, apiKey.getAccountId(), keyId);
                    throw new TokenException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                            "Token account claim does not match the issued key.");
                }
            }
            case "BUSINESS" -> {
                Object firmClaim = claims.get("firm");
                if (firmClaim == null) {
                    throw new TokenException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                            "Token is missing required 'firm' claim.");
                }
                if (!(firmClaim instanceof Number n)) {
                    throw new TokenException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                            "Token claim 'firm' must be a number.");
                }
                firmId = n.longValue();
                // Defence in depth: pin the signed firm claim to api_keys.firm_id.
                if (apiKey.getFirmId() != null && apiKey.getFirmId().longValue() != firmId) {
                    log.warn("Firm claim mismatch: JWT firm={} DB firm_id={} keyId={}",
                            firmId, apiKey.getFirmId(), keyId);
                    throw new TokenException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                            "Token firm claim does not match the issued key.");
                }
            }
            case "SERVICE" -> {
                // SERVICE/admin keys are not scoped to a single account or firm — they
                // carry neither an 'acc' nor a 'firm' claim. Authorisation for the admin
                // endpoints is enforced per-endpoint (requireServiceKey), not via a
                // scoped claim here. accountId/firmId stay null.
                //
                // SERVICE is the most powerful credential in the system, so leave a trail
                // of every use (ADT service-key-keytype-not-pinned) — an unexpected
                // SERVICE verification (e.g. an api_keys row whose key_type was flipped)
                // is then visible. INFO rather than WARN: the explorer uses a SERVICE key
                // on every admin call, so WARN would be noise that buries the signal.
                log.info("SERVICE token verified: keyId={} owner={}", keyId, ownerUuid);
            }
            default -> throw new TokenException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN",
                    "Token has an unrecognised key type.");
        }

        return new VerifiedToken(keyId, ownerUuid, claimedType, accountId, firmId);
    }
}
