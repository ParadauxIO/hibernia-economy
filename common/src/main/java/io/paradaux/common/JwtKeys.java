package io.paradaux.common;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Single source of truth for deriving the HMAC-SHA256 signing key from the
 * configured JWT secret (ADT-22). Mint (treasury-api-plugin) and verify
 * (treasury-rest-api) both call this, so the derivation can't drift across the
 * trust boundary.
 *
 * <h2>Key material (ADT-23)</h2>
 * Prefer real random key material over a human passphrase:
 * <ul>
 *   <li>A secret of the form {@code base64:<b64>} is decoded and used <em>directly</em>
 *       as the HMAC key — it must be ≥ 32 bytes (256 bits). Generate one with
 *       {@code openssl rand -base64 48} and configure it as
 *       {@code base64:<that value>}.</li>
 *   <li>Any other secret falls back to the legacy {@code SHA-256(secret)}
 *       derivation, logging a warning. A single SHA-256 of a low-entropy
 *       passphrase is brute-forceable offline, so this path is deprecated.</li>
 * </ul>
 *
 * <p>The {@code base64:} prefix is an explicit opt-in so deploying this code does
 * <strong>not</strong> silently change the key for an existing passphrase secret
 * (which would invalidate every live token). <strong>Rotation procedure:</strong>
 * generate a {@code base64:} secret, set it as the JWT secret on the REST API
 * (env {@code JWT_SECRET}) <em>and</em> the plugin ({@code api.jwt-secret})
 * together, restart both; existing 180-day tokens stop verifying, so users
 * reissue in-game.
 */
public final class JwtKeys {

    /** Minimum HMAC key length: 256 bits. */
    public static final int MIN_KEY_BYTES = 32;

    private static final String BASE64_PREFIX = "base64:";
    private static final System.Logger LOG = System.getLogger(JwtKeys.class.getName());

    private JwtKeys() {}

    public static SecretKey deriveHmacKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret must be set");
        }
        if (secret.startsWith(BASE64_PREFIX)) {
            byte[] keyMaterial = Base64.getDecoder().decode(secret.substring(BASE64_PREFIX.length()).trim());
            if (keyMaterial.length < MIN_KEY_BYTES) {
                throw new IllegalStateException("JWT 'base64:' key material must be at least "
                        + MIN_KEY_BYTES + " bytes (got " + keyMaterial.length + "); "
                        + "generate one with `openssl rand -base64 48`");
            }
            return new SecretKeySpec(keyMaterial, "HmacSHA256");
        }

        // Legacy: a single SHA-256 of a (possibly low-entropy) passphrase. Kept so
        // deploying this code doesn't break a running deployment, but deprecated —
        // rotate JWT_SECRET to a `base64:<random>` value (see class javadoc).
        LOG.log(System.Logger.Level.WARNING,
                "Deriving the JWT key from a passphrase via the legacy SHA-256 KDF. "
                + "Rotate the JWT secret to random key material: `base64:$(openssl rand -base64 48)`, "
                + "set on the plugin AND the REST API together.");
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "HmacSHA256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
