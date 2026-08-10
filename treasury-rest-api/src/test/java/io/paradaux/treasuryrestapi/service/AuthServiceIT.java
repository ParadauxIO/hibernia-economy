package io.paradaux.treasuryrestapi.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.paradaux.treasuryrestapi.dto.RotateResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import javax.crypto.SecretKey;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Integration tests for {@link AuthService} rotation (finding
 * treasury-rest-api/testing/0005): {@code rotateToken} (owner self-rotate) and
 * {@code adminForceRotate} (admin invalidation). Both replace the DB jti so the old
 * token is superseded; only the owner path returns a usable token. Drives the real
 * service + ApiKeyMapper + the production signing key against the embedded MariaDB,
 * and verifies the minted token against the same key the verifier uses.
 */
class AuthServiceIT extends EmbeddedDbIT {

    @Autowired private AuthService authService;
    @Autowired private SecretKey signingKey;

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDateTime FUTURE = LocalDateTime.now().plusDays(30);

    private VerifiedToken personalPrincipal(long keyId, long accountId) {
        return new VerifiedToken(keyId, OWNER, "PERSONAL", accountId, null);
    }

    private Claims parse(String token) {
        Jws<Claims> jws = Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token);
        return jws.getPayload();
    }

    // ── rotateToken ───────────────────────────────────────────────────────────────

    @Test
    void rotateToken_replacesJtiAndMintsVerifiableToken() {
        insertApiKey(5, "PERSONAL", OWNER, "old-jti-0000", false, FUTURE);

        RotateResponse resp = authService.rotateToken(personalPrincipal(5, 42));

        // A fresh token is returned, and the DB jti no longer equals the old one.
        assertThat(resp.token()).isNotBlank();
        String newJti = jwtIdOf(5);
        assertThat(newJti).isNotEqualTo("old-jti-0000");

        // The minted token verifies under the production key and carries the new jti,
        // the owner subject, the PERSONAL type and the acc claim.
        Claims claims = parse(resp.token());
        assertThat(claims.getId()).isEqualTo(newJti);
        assertThat(claims.getSubject()).isEqualTo(OWNER.toString());
        assertThat(claims.get("type", String.class)).isEqualTo("PERSONAL");
        assertThat(((Number) claims.get("acc")).longValue()).isEqualTo(42L);
        assertThat(resp.keyId()).isEqualTo(5L);
    }

    @Test
    void rotateToken_onRevokedKey_401_andJtiUnchanged() {
        insertApiKey(5, "PERSONAL", OWNER, "old-jti-0000", true, FUTURE);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> authService.rotateToken(personalPrincipal(5, 42)));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getErrorCode()).isEqualTo("TOKEN_REVOKED");
        assertThat(jwtIdOf(5)).isEqualTo("old-jti-0000"); // no rotation happened
    }

    @Test
    void rotateToken_missingKey_401() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> authService.rotateToken(personalPrincipal(999, 42)));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ex.getErrorCode()).isEqualTo("TOKEN_REVOKED");
    }

    // ── adminForceRotate ──────────────────────────────────────────────────────────

    @Test
    void adminForceRotate_replacesJtiWithoutReturningToken() {
        insertApiKey(7, "PERSONAL", OWNER, "old-jti-1111", false, FUTURE);

        authService.adminForceRotate(7);

        // jti replaced (old token superseded) — the method returns only the new expiry,
        // never a token (an admin must not see another user's secret).
        assertThat(jwtIdOf(7)).isNotEqualTo("old-jti-1111");
    }

    @Test
    void adminForceRotate_missingKey_404() {
        ApiException ex = catchThrowableOfType(ApiException.class, () -> authService.adminForceRotate(999));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("KEY_NOT_FOUND");
    }

    @Test
    void adminForceRotate_revokedKey_409_andJtiUnchanged() {
        insertApiKey(7, "PERSONAL", OWNER, "old-jti-1111", true, FUTURE);

        ApiException ex = catchThrowableOfType(ApiException.class, () -> authService.adminForceRotate(7));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ex.getErrorCode()).isEqualTo("TOKEN_REVOKED");
        assertThat(jwtIdOf(7)).isEqualTo("old-jti-1111");
    }
}
