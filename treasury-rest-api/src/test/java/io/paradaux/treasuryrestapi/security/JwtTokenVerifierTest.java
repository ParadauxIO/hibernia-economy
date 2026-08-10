package io.paradaux.treasuryrestapi.security;

import io.jsonwebtoken.Jwts;
import io.paradaux.common.JwtKeys;
import io.paradaux.treasuryrestapi.mapper.ApiKeyMapper;
import io.paradaux.treasuryrestapi.model.ApiKey;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Unit tests for the JWT verification pipeline — the security core that gates every
 * endpoint. No Spring, no DB: the {@link ApiKeyMapper} is a hand-rolled stub so each
 * pipeline step can be exercised in isolation. The signing key is derived exactly as
 * {@code JwtConfig} does (SHA-256 of the secret), and tokens are minted the same way
 * {@code AuthService} mints them, so these tokens are byte-for-byte what production
 * verifies.
 */
class JwtTokenVerifierTest {

    private static final SecretKey KEY = deriveKey("test-jwt-secret-32-or-more-characters-of-deterministic-fixture-data");
    private static final SecretKey WRONG_KEY = deriveKey("a-totally-different-secret-of-sufficient-length-for-hs256-xx");

    private static final long KID = 7L;
    private static final UUID OWNER = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String JTI = "a8098c1a-f86e-11da-bd1a-00112444be1e";
    private static final long ACC = 42L;
    private static final long FIRM = 9L;

    // ── happy paths ─────────────────────────────────────────────────────────────

    @Test
    void validPersonalToken_returnsVerifiedToken() {
        JwtTokenVerifier verifier = verifierFor(personalKey());
        String token = personalToken(t -> {});

        VerifiedToken vt = verifier.verify(token);

        assertThat(vt.keyId()).isEqualTo(KID);
        assertThat(vt.ownerUuid()).isEqualTo(OWNER);
        assertThat(vt.keyType()).isEqualTo("PERSONAL");
        assertThat(vt.accountId()).isEqualTo(ACC);
        assertThat(vt.firmId()).isNull();
    }

    @Test
    void validBusinessToken_returnsFirmScopedVerifiedToken() {
        JwtTokenVerifier verifier = verifierFor(businessKey());
        String token = businessToken(t -> {});

        VerifiedToken vt = verifier.verify(token);

        assertThat(vt.keyType()).isEqualTo("BUSINESS");
        assertThat(vt.firmId()).isEqualTo(FIRM);
        assertThat(vt.accountId()).isNull();
    }

    @Test
    void tokenMintedWithJwtKeysBase64Derivation_verifiesEndToEnd() {
        // Cross-language auth-boundary guard (ADT no-crosslang-token-roundtrip-test):
        // the mint side (treasury-api-plugin) and this verify side both derive the HMAC
        // key from the SAME io.paradaux.common.JwtKeys. Exercise the production-recommended
        // base64: secret end-to-end so a derivation regression can't pass while silently
        // breaking every API key. (The other tests use the legacy SHA-256 fixture key.)
        String base64Secret = "base64:" + Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)); // 32 bytes
        SecretKey sharedKey = JwtKeys.deriveHmacKey(base64Secret);

        JwtTokenVerifier verifier = new JwtTokenVerifier(sharedKey, stub(keyId -> keyId == KID ? personalKey() : null));
        String token = baseBuilder("PERSONAL", b -> b.claim("acc", ACC))
                .signWith(sharedKey, Jwts.SIG.HS256).compact();

        VerifiedToken vt = verifier.verify(token);
        assertThat(vt.keyType()).isEqualTo("PERSONAL");
        assertThat(vt.accountId()).isEqualTo(ACC);
    }

    // ── F5: scoped-claim must agree with the DB row (defence in depth) ───────────

    @Test
    void accClaimNotMatchingDbAccount_rejected() {
        JwtTokenVerifier verifier = verifierFor(personalKey());           // DB account_id = 42
        String token = personalToken(b -> b.claim("acc", 99));            // token says 99

        assertInvalid(verifier, token, "INVALID_TOKEN");
    }

    @Test
    void firmClaimNotMatchingDbFirm_rejected() {
        JwtTokenVerifier verifier = verifierFor(businessKey());           // DB firm_id = 9
        String token = businessToken(b -> b.claim("firm", 8));            // token says 8

        assertInvalid(verifier, token, "INVALID_TOKEN");
    }

    // ── signature / parse / expiry ──────────────────────────────────────────────

    @Test
    void badSignature_rejectedAsInvalidToken() {
        JwtTokenVerifier verifier = verifierFor(personalKey());
        String token = baseBuilder("PERSONAL", b -> b.claim("acc", ACC)).signWith(WRONG_KEY, Jwts.SIG.HS256).compact();

        assertInvalid(verifier, token, "INVALID_TOKEN");
    }

    @Test
    void unsignedToken_rejectedAsInvalidToken() {
        JwtTokenVerifier verifier = verifierFor(personalKey());
        // alg:none — no signWith. parseSignedClaims must refuse it.
        String token = baseBuilder("PERSONAL", b -> b.claim("acc", ACC)).compact();

        assertInvalid(verifier, token, "INVALID_TOKEN");
    }

    @Test
    void expiredToken_rejectedAsTokenExpired() {
        JwtTokenVerifier verifier = verifierFor(personalKey());
        Instant past = Instant.now().minus(10, ChronoUnit.DAYS);
        String token = Jwts.builder()
                .header().add("kid", String.valueOf(KID)).and()
                .subject(OWNER.toString()).claim("type", "PERSONAL").claim("acc", ACC).id(JTI)
                .issuedAt(Date.from(past.minus(1, ChronoUnit.DAYS)))
                .expiration(Date.from(past))
                .signWith(KEY, Jwts.SIG.HS256).compact();

        assertInvalid(verifier, token, "TOKEN_EXPIRED");
    }

    // ── kid header ──────────────────────────────────────────────────────────────

    @Test
    void missingKid_rejectedAsInvalidToken() {
        JwtTokenVerifier verifier = verifierFor(personalKey());
        String token = Jwts.builder()
                .subject(OWNER.toString()).claim("type", "PERSONAL").claim("acc", ACC).id(JTI)
                .issuedAt(Date.from(Instant.now())).expiration(future())
                .signWith(KEY, Jwts.SIG.HS256).compact();

        assertInvalid(verifier, token, "INVALID_TOKEN");
    }

    @Test
    void nonNumericKid_rejectedAsInvalidToken() {
        JwtTokenVerifier verifier = verifierFor(personalKey());
        String token = baseBuilder("PERSONAL", b -> b.claim("acc", ACC), "not-a-number")
                .signWith(KEY, Jwts.SIG.HS256).compact();

        assertInvalid(verifier, token, "INVALID_TOKEN");
    }

    @Test
    void nonPositiveKid_rejectedAsInvalidToken() {
        JwtTokenVerifier verifier = verifierFor(personalKey());
        String token = baseBuilder("PERSONAL", b -> b.claim("acc", ACC), "0")
                .signWith(KEY, Jwts.SIG.HS256).compact();

        assertInvalid(verifier, token, "INVALID_TOKEN");
    }

    // ── DB-row checks (steps 5–7) ───────────────────────────────────────────────

    @Test
    void keyNotFoundInDb_rejectedAsInvalidToken() {
        JwtTokenVerifier verifier = new JwtTokenVerifier(KEY, stub(keyId -> null)); // no row
        String token = personalToken(t -> {});

        assertInvalid(verifier, token, "INVALID_TOKEN");
    }

    @Test
    void revokedKey_rejectedAsTokenRevoked() {
        ApiKey revoked = personalKey();
        revoked.setRevoked(true);
        JwtTokenVerifier verifier = verifierFor(revoked);
        String token = personalToken(t -> {});

        assertInvalid(verifier, token, "TOKEN_REVOKED");
    }

    @Test
    void supersededJti_rejectedAsTokenSuperseded() {
        ApiKey key = personalKey();
        key.setJwtId(UUID.randomUUID().toString());                       // DB jti rotated away
        JwtTokenVerifier verifier = verifierFor(key);
        String token = personalToken(t -> {});                           // old jti

        assertInvalid(verifier, token, "TOKEN_SUPERSEDED");
    }

    // ── type claim (step 8) ─────────────────────────────────────────────────────

    @Test
    void typeClaimNotMatchingDb_rejectedAsInvalidToken() {
        JwtTokenVerifier verifier = verifierFor(businessKey());          // DB key_type = BUSINESS
        String token = personalToken(t -> {});                          // token says PERSONAL

        assertInvalid(verifier, token, "INVALID_TOKEN");
    }

    @Test
    void missingTypeClaim_rejectedAsInvalidToken() {
        JwtTokenVerifier verifier = verifierFor(personalKey());
        String token = Jwts.builder()
                .header().add("kid", String.valueOf(KID)).and()
                .subject(OWNER.toString()).claim("acc", ACC).id(JTI)     // no type
                .issuedAt(Date.from(Instant.now())).expiration(future())
                .signWith(KEY, Jwts.SIG.HS256).compact();

        assertInvalid(verifier, token, "INVALID_TOKEN");
    }

    @Test
    void unrecognisedType_rejectedAsInvalidToken() {
        ApiKey weird = personalKey();
        weird.setKeyType("WEIRD");                                       // matches token so step 8 passes
        JwtTokenVerifier verifier = verifierFor(weird);
        String token = baseBuilder("WEIRD", b -> b.claim("acc", ACC))
                .signWith(KEY, Jwts.SIG.HS256).compact();

        assertInvalid(verifier, token, "INVALID_TOKEN");
    }

    // ── subject / scoped claims (step 9) ────────────────────────────────────────

    @Test
    void missingSubject_rejectedAsInvalidToken() {
        JwtTokenVerifier verifier = verifierFor(personalKey());
        String token = Jwts.builder()
                .header().add("kid", String.valueOf(KID)).and()
                .claim("type", "PERSONAL").claim("acc", ACC).id(JTI)     // no subject
                .issuedAt(Date.from(Instant.now())).expiration(future())
                .signWith(KEY, Jwts.SIG.HS256).compact();

        assertInvalid(verifier, token, "INVALID_TOKEN");
    }

    @Test
    void malformedSubjectUuid_rejectedAsInvalidToken() {
        JwtTokenVerifier verifier = verifierFor(personalKey());
        String token = Jwts.builder()
                .header().add("kid", String.valueOf(KID)).and()
                .subject("not-a-uuid").claim("type", "PERSONAL").claim("acc", ACC).id(JTI)
                .issuedAt(Date.from(Instant.now())).expiration(future())
                .signWith(KEY, Jwts.SIG.HS256).compact();

        assertInvalid(verifier, token, "INVALID_TOKEN");
    }

    @Test
    void personalTokenMissingAcc_rejectedAsInvalidToken() {
        JwtTokenVerifier verifier = verifierFor(personalKey());
        String token = baseBuilder("PERSONAL", b -> {})                  // no acc
                .signWith(KEY, Jwts.SIG.HS256).compact();

        assertInvalid(verifier, token, "INVALID_TOKEN");
    }

    @Test
    void accClaimNotANumber_rejectedAsInvalidToken() {
        JwtTokenVerifier verifier = verifierFor(personalKey());
        String token = baseBuilder("PERSONAL", b -> b.claim("acc", "forty-two"))
                .signWith(KEY, Jwts.SIG.HS256).compact();

        assertInvalid(verifier, token, "INVALID_TOKEN");
    }

    @Test
    void businessTokenMissingFirm_rejectedAsInvalidToken() {
        JwtTokenVerifier verifier = verifierFor(businessKey());
        String token = baseBuilder("BUSINESS", b -> {})                  // no firm
                .signWith(KEY, Jwts.SIG.HS256).compact();

        assertInvalid(verifier, token, "INVALID_TOKEN");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static void assertInvalid(JwtTokenVerifier verifier, String token, String expectedCode) {
        TokenException ex = catchThrowableOfType(TokenException.class, () -> verifier.verify(token));
        assertThat(ex).as("expected a TokenException").isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getErrorCode()).isEqualTo(expectedCode);
    }

    private JwtTokenVerifier verifierFor(ApiKey key) {
        return new JwtTokenVerifier(KEY, stub(keyId -> keyId == KID ? key : null));
    }

    /** Minimal hand-rolled ApiKeyMapper; only findByKeyId is exercised by the verifier. */
    private static ApiKeyMapper stub(java.util.function.LongFunction<ApiKey> byKeyId) {
        return new ApiKeyMapper() {
            @Override public ApiKey findByKeyId(long keyId) { return byKeyId.apply(keyId); }
            @Override public ApiKey findByKeyIdForUpdate(long keyId) { throw new UnsupportedOperationException(); }
            @Override public int rotateKey(long keyId, String jwtId,
                                           java.time.LocalDateTime issuedAt, java.time.LocalDateTime expiresAt) {
                throw new UnsupportedOperationException();
            }
            @Override public int revoke(long keyId) { throw new UnsupportedOperationException(); }
        };
    }

    private static ApiKey personalKey() {
        return ApiKey.builder().keyId(KID).keyType("PERSONAL").ownerUuid(OWNER)
                .accountId((int) ACC).firmId(null).jwtId(JTI).revoked(false).build();
    }

    private static ApiKey businessKey() {
        return ApiKey.builder().keyId(KID).keyType("BUSINESS").ownerUuid(OWNER)
                .accountId(null).firmId((int) FIRM).jwtId(JTI).revoked(false).build();
    }

    private static String personalToken(Consumer<io.jsonwebtoken.JwtBuilder> extra) {
        io.jsonwebtoken.JwtBuilder b = baseBuilder("PERSONAL", x -> x.claim("acc", ACC));
        extra.accept(b);
        return b.signWith(KEY, Jwts.SIG.HS256).compact();
    }

    private static String businessToken(Consumer<io.jsonwebtoken.JwtBuilder> extra) {
        io.jsonwebtoken.JwtBuilder b = baseBuilder("BUSINESS", x -> x.claim("firm", FIRM));
        extra.accept(b);
        return b.signWith(KEY, Jwts.SIG.HS256).compact();
    }

    private static io.jsonwebtoken.JwtBuilder baseBuilder(String type, Consumer<io.jsonwebtoken.JwtBuilder> scoped) {
        return baseBuilder(type, scoped, String.valueOf(KID));
    }

    private static io.jsonwebtoken.JwtBuilder baseBuilder(String type, Consumer<io.jsonwebtoken.JwtBuilder> scoped, String kid) {
        io.jsonwebtoken.JwtBuilder b = Jwts.builder()
                .header().add("kid", kid).and()
                .subject(OWNER.toString())
                .claim("type", type)
                .id(JTI)
                .issuedAt(Date.from(Instant.now()))
                .expiration(future());
        scoped.accept(b);
        return b;
    }

    private static Date future() {
        return Date.from(Instant.now().plus(180, ChronoUnit.DAYS));
    }

    private static SecretKey deriveKey(String secret) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return io.jsonwebtoken.security.Keys.hmacShaKeyFor(bytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
