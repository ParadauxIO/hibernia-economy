package io.paradaux.treasuryrestapi.config;

import io.paradaux.common.JwtKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;

@Configuration
public class JwtConfig {

    /**
     * The placeholder shipped in the default-profile {@code application.yaml}.
     * Production must override this via {@code JWT_SECRET}; if the literal
     * placeholder reaches a running app, fail-fast at bean creation rather than
     * boot a service signing tokens with a known secret.
     */
    private static final String PLACEHOLDER_SECRET =
            "change-me-please-use-a-long-random-secret-key";

    /** Minimum acceptable secret length, in characters. 32 = ~192 bits of entropy at base64. */
    private static final int MIN_SECRET_LENGTH = 32;

    @Value("${api.jwt-secret}")
    private String jwtSecret;

    /**
     * Derives the HMAC-SHA256 signing key via the shared {@link JwtKeys#deriveHmacKey}
     * (ADT-22) — the exact same derivation the in-game plugin uses to sign tokens,
     * so the two can no longer drift apart independently.
     *
     * <p>Validates the configured secret before deriving the key:
     * <ul>
     *   <li>refuses to start if the secret is the bundled placeholder value, and</li>
     *   <li>refuses to start if the secret is shorter than {@value #MIN_SECRET_LENGTH}
     *       characters (defense-in-depth: SHA-256 always produces a 256-bit key,
     *       but a short input means the signing key is fundamentally low-entropy).</li>
     * </ul>
     */
    @Bean
    public SecretKey jwtSigningKey() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "api.jwt-secret is not set. Configure JWT_SECRET in the active profile "
                    + "(see application-prod.yaml).");
        }
        if (PLACEHOLDER_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "api.jwt-secret is the placeholder value from application.yaml. "
                    + "Set the JWT_SECRET environment variable, or pin SPRING_PROFILES_ACTIVE=prod "
                    + "and configure JWT_SECRET there.");
        }
        if (jwtSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "api.jwt-secret must be at least " + MIN_SECRET_LENGTH + " characters of "
                    + "high-entropy random data (currently " + jwtSecret.length() + ").");
        }

        return JwtKeys.deriveHmacKey(jwtSecret);
    }
}
