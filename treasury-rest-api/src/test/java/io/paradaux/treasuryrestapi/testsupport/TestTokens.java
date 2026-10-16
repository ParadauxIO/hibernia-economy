package io.paradaux.treasuryrestapi.testsupport;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;

/**
 * Mints API-key JWTs exactly as the in-game issuer does (HS256, {@code kid} = api_keys
 * row, {@code jti} pinned to that row, {@code type} + scoped claim) for HTTP-level
 * security tests, plus the broken variants the filter must reject.
 *
 * <p>{@link #seedAll()} inserts one live {@code api_keys} row per key type so the
 * verifier's DB checks pass; call it after the per-test truncation.
 */
public final class TestTokens {

    /** {@code EmbeddedDbIT#insertApiKey}, passed as a method reference. */
    @FunctionalInterface
    public interface KeySeeder {
        void insert(int keyId, String keyType, UUID owner, String jwtId, boolean revoked, LocalDateTime expiresAt);
    }

    public static final int PERSONAL_KEY = 9001;
    public static final int BUSINESS_KEY = 9002;
    public static final int GOVERNMENT_KEY = 9003;
    public static final int SERVICE_KEY = 9004;

    public static final UUID PERSONAL_OWNER = UUID.fromString("7e570000-0000-0000-0000-000000000001");
    public static final UUID BUSINESS_OWNER = UUID.fromString("7e570000-0000-0000-0000-000000000002");
    public static final UUID GOVERNMENT_OWNER = UUID.fromString("7e570000-0000-0000-0000-000000000003");
    public static final UUID SERVICE_OWNER = UUID.fromString("7e570000-0000-0000-0000-000000000004");

    /** Scoped claims; no rows need to exist for them (api_keys.account_id/firm_id stay NULL). */
    public static final long PERSONAL_ACCOUNT = 9001;
    public static final long GOVERNMENT_ACCOUNT = 9003;
    public static final long BUSINESS_FIRM = 9002;

    /** A different, valid-length HS256 key: right shape, wrong secret. */
    private static final SecretKey WRONG_KEY = Keys.hmacShaKeyFor(
            "an-entirely-different-secret-that-is-long-enough-for-hs256".getBytes(StandardCharsets.UTF_8));

    private final SecretKey signingKey;
    private final KeySeeder seeder;

    public TestTokens(SecretKey signingKey, KeySeeder seeder) {
        this.signingKey = signingKey;
        this.seeder = seeder;
    }

    public void seedAll() {
        LocalDateTime expires = LocalDateTime.now().plusDays(30);
        seeder.insert(PERSONAL_KEY, "PERSONAL", PERSONAL_OWNER, jti(PERSONAL_KEY), false, expires);
        seeder.insert(BUSINESS_KEY, "BUSINESS", BUSINESS_OWNER, jti(BUSINESS_KEY), false, expires);
        seeder.insert(GOVERNMENT_KEY, "GOVERNMENT", GOVERNMENT_OWNER, jti(GOVERNMENT_KEY), false, expires);
        seeder.insert(SERVICE_KEY, "SERVICE", SERVICE_OWNER, jti(SERVICE_KEY), false, expires);
    }

    public String personal() { return builder(PERSONAL_KEY, Duration.ofHours(1)).signWith(signingKey, Jwts.SIG.HS256).compact(); }
    public String business() { return builder(BUSINESS_KEY, Duration.ofHours(1)).signWith(signingKey, Jwts.SIG.HS256).compact(); }
    public String government() { return builder(GOVERNMENT_KEY, Duration.ofHours(1)).signWith(signingKey, Jwts.SIG.HS256).compact(); }
    public String service() { return builder(SERVICE_KEY, Duration.ofHours(1)).signWith(signingKey, Jwts.SIG.HS256).compact(); }

    /** Correctly signed PERSONAL token whose {@code exp} has passed. */
    public String expiredPersonal() {
        return builder(PERSONAL_KEY, Duration.ofMinutes(-5)).signWith(signingKey, Jwts.SIG.HS256).compact();
    }

    /** PERSONAL token with valid claims, signed with a key the server doesn't hold. */
    public String personalSignedWithWrongKey() {
        return builder(PERSONAL_KEY, Duration.ofHours(1)).signWith(WRONG_KEY, Jwts.SIG.HS256).compact();
    }

    /** PERSONAL token with valid claims and {@code alg: none} (no signature at all). */
    public String personalUnsigned() {
        return builder(PERSONAL_KEY, Duration.ofHours(1)).compact();
    }

    private static String jti(int keyId) {
        return "test-tokens-jti-" + keyId;
    }

    private static JwtBuilder builder(int keyId, Duration ttl) {
        Instant now = Instant.now();
        JwtBuilder b = Jwts.builder()
                .header().add("kid", Integer.toString(keyId)).and()
                .id(jti(keyId))
                .issuedAt(Date.from(now.minus(Duration.ofMinutes(10))))
                .expiration(Date.from(now.plus(ttl)));
        return switch (keyId) {
            case PERSONAL_KEY -> b.subject(PERSONAL_OWNER.toString()).claim("type", "PERSONAL").claim("acc", PERSONAL_ACCOUNT);
            case BUSINESS_KEY -> b.subject(BUSINESS_OWNER.toString()).claim("type", "BUSINESS").claim("firm", BUSINESS_FIRM);
            case GOVERNMENT_KEY -> b.subject(GOVERNMENT_OWNER.toString()).claim("type", "GOVERNMENT").claim("acc", GOVERNMENT_ACCOUNT);
            case SERVICE_KEY -> b.subject(SERVICE_OWNER.toString()).claim("type", "SERVICE");
            default -> throw new IllegalArgumentException("unknown test key " + keyId);
        };
    }
}
