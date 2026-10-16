package net.democracycraft.inttests.support;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Crafts JWTs the way {@code TreasuryAPI} (the in-game plugin) does, so
 * security integration tests can mint tokens with arbitrary claims —
 * past expiry, wrong secret, unknown kid, type-claim mismatch, etc. —
 * without having to drive the in-game issue command.
 *
 * <p>The signing key is derived as {@code HMAC-SHA256(SHA-256(secret))},
 * matching {@code JwtConfig.jwtSigningKey()} in the REST API. The default
 * secret is the dev value baked into the dev server's
 * {@code TreasuryAPI/config.yml}; override at test time via system
 * property {@code restapi.jwt.secret} or env var
 * {@code RESTAPI_JWT_SECRET}.
 *
 * <p>This class is for negative-path tests <strong>only</strong>. Use
 * {@link ApiTokens#issuePersonal} / {@link ApiTokens#issueBusiness} when
 * you need a token that will pass verification — those drive the real
 * plugin issuance flow, populate the {@code api_keys} row, and return
 * the token from the DB.
 */
public final class JwtForge {

    /** Same value as {@code server/plugins/TreasuryAPI/config.yml api.jwt-secret}. */
    private static final String DEFAULT_DEV_SECRET =
            "dev-jwt-secret-change-in-production-please-use-32-plus-random-bytes";

    private static final String SECRET = firstNonBlank(
            System.getProperty("restapi.jwt.secret"),
            System.getenv("RESTAPI_JWT_SECRET"),
            DEFAULT_DEV_SECRET);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder URL_ENC = Base64.getUrlEncoder().withoutPadding();

    /**
     * Mints a token with a custom expiry. Use a negative duration to
     * produce an already-expired token. Claims are filled to satisfy the
     * verifier up to the {@code exp} check; tests focused on later checks
     * (jti, type, acc/firm) should call {@link #signCustom(Map, long, Duration, String)}.
     *
     * @param keyId   value to put in both the {@code kid} header and
     *                {@code keyId} claim — point this at a real
     *                {@code api_keys.key_id} when the test wants to reach
     *                the DB-lookup checks.
     * @param jti     value for the {@code jti} claim. Match the row's
     *                {@code jwt_id} for jti freshness to pass.
     * @param ownerUuid subject of the token — the player UUID.
     * @param keyType   one of {@code PERSONAL} / {@code BUSINESS} / {@code GOVERNMENT}.
     * @param accountIdOrFirmId — for PERSONAL / GOVERNMENT, the {@code acc} value;
     *                  for BUSINESS, the {@code firm} value.
     * @param ttl       token lifetime relative to now. Negative ⇒ already expired.
     */
    public static String sign(long keyId, String jti, UUID ownerUuid, String keyType,
                              long accountIdOrFirmId, Duration ttl) {
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "treasury-api");
        claims.put("sub", ownerUuid.toString());
        claims.put("jti", jti);
        claims.put("type", keyType);
        if ("BUSINESS".equals(keyType)) {
            claims.put("firm", accountIdOrFirmId);
        } else {
            claims.put("acc", accountIdOrFirmId);
        }
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(ttl).getEpochSecond());
        return signCustom(claims, keyId, SECRET);
    }

    /**
     * Mints a SERVICE (admin) token the way {@code economy-explorer/scripts/mint-service-token.mjs}
     * does: claims {@code sub}, {@code type=SERVICE}, {@code jti}, {@code iat}, {@code exp} and
     * no {@code acc}/{@code firm} scope. Nothing in-game can issue SERVICE keys, so this is the
     * only way the suite gets one; it verifies once the {@code api_keys} row carries the same
     * {@code jti} — use {@link ApiTokens#mintServiceKey}, which writes the row.
     */
    public static String signService(long keyId, String jti, UUID ownerUuid, Duration ttl) {
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", ownerUuid.toString());
        claims.put("type", "SERVICE");
        claims.put("jti", jti);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(ttl).getEpochSecond());
        return signCustom(claims, keyId, SECRET);
    }

    /**
     * Mints a token signed with an explicitly-supplied secret — for the
     * wrong-secret-rejection test. Pass any secret string ≥ 32 chars.
     */
    public static String signCustom(Map<String, Object> claims, long keyId, String secret) {
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");
            header.put("kid", String.valueOf(keyId));

            String headerB64 = URL_ENC.encodeToString(JSON.writeValueAsBytes(header));
            String payloadB64 = URL_ENC.encodeToString(JSON.writeValueAsBytes(claims));
            String signingInput = headerB64 + "." + payloadB64;

            // Same key derivation as the REST API's JwtConfig: HMAC-SHA256
            // over the SHA-256 hash of the configured secret.
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            byte[] sig = mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));

            return signingInput + "." + URL_ENC.encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("JWT forge failed", e);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private JwtForge() {}
}
