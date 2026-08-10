package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.WebhookCreateRequest;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.model.RateLimitOverride;
import io.paradaux.treasuryrestapi.ratelimit.RateLimitOverrideService;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Integration tests for the SERVICE-scoped admin single-writers (finding
 * treasury-rest-api/testing/0007): AdminWebhookService, AdminApiKeyService,
 * AdminRateLimitService. Each is exercised for its authz gate (SERVICE key required)
 * and its persisted effect against the embedded MariaDB.
 */
class AdminServicesIT extends EmbeddedDbIT {

    @Autowired private AdminWebhookService adminWebhook;
    @Autowired private AdminApiKeyService adminApiKey;
    @Autowired private AdminRateLimitService adminRateLimit;
    @Autowired private RateLimitOverrideService overrides;

    private static final UUID ADMIN = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String PUBLIC_URL = "https://93.184.216.34/hook"; // literal public IP → SSRF-clean offline
    private static final LocalDateTime FUTURE = LocalDateTime.now().plusDays(30);

    private VerifiedToken service() { return new VerifiedToken(1L, ADMIN, "SERVICE", null, null); }
    private VerifiedToken personal() { return new VerifiedToken(2L, ADMIN, "PERSONAL", 1L, null); }

    private WebhookCreateRequest req(String url, String secret) {
        return new WebhookCreateRequest(OWNER, "PERSONAL", 42, null, url, secret);
    }

    // ── AdminWebhookService ────────────────────────────────────────────────────────

    @Test
    void createWebhook_persistsRow() {
        long id = adminWebhook.create(service(), req(PUBLIC_URL, "s3cret"));
        assertThat(id).isPositive();
        assertThat(rowCount("webhook_subscription")).isEqualTo(1);
    }

    @Test
    void createWebhook_requiresServiceKey() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> adminWebhook.create(personal(), req(PUBLIC_URL, "s")));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rowCount("webhook_subscription")).isZero();
    }

    @Test
    void createWebhook_rejectsPrivateUrl() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> adminWebhook.create(service(), req("https://127.0.0.1/x", "s")));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rowCount("webhook_subscription")).isZero();
    }

    @Test
    void createWebhook_rejectsOverlongSecret() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> adminWebhook.create(service(), req(PUBLIC_URL, "x".repeat(65))));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_BODY");
    }

    @Test
    void createWebhook_rejectsKeyTypeOutsideEnum() {
        // key_type is ENUM('PERSONAL','BUSINESS','GOVERNMENT'); anything else (here SYSTEM)
        // must be a clean 400, not a driver data-truncation 500. No row persisted.
        WebhookCreateRequest bad = new WebhookCreateRequest(OWNER, "SYSTEM", 42, null, PUBLIC_URL, "s");
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> adminWebhook.create(service(), bad));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getErrorCode()).isEqualTo("INVALID_BODY");
        assertThat(rowCount("webhook_subscription")).isZero();
    }

    @Test
    void setUrl_ownerScoped_onlyAffectsOwnedRow() {
        long id = adminWebhook.create(service(), req(PUBLIC_URL, "s"));
        // Wrong owner → 0 rows affected (self-service scope not matched).
        int wrong = adminWebhook.setUrl(service(), id,
                UUID.fromString("00000000-0000-0000-0000-000000000000"), "https://93.184.216.34/new");
        assertThat(wrong).isZero();
        // Correct owner → 1 row, URL persisted.
        int right = adminWebhook.setUrl(service(), id, OWNER, "https://93.184.216.34/new");
        assertThat(right).isEqualTo(1);
    }

    @Test
    void setActive_fleetOp_nullOwner_affectsRow() {
        long id = adminWebhook.create(service(), req(PUBLIC_URL, "s"));
        int n = adminWebhook.setActive(service(), id, null, false);
        assertThat(n).isEqualTo(1);
        assertThat(subscriptionActive(id)).isFalse();
    }

    @Test
    void deleteWebhook_removesRow() {
        long id = adminWebhook.create(service(), req(PUBLIC_URL, "s"));
        int n = adminWebhook.delete(service(), id, null);
        assertThat(n).isEqualTo(1);
        assertThat(rowCount("webhook_subscription")).isZero();
    }

    @Test
    void setSecret_requiresServiceKey() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> adminWebhook.setSecret(personal(), 1L, null, "s"));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── AdminApiKeyService ─────────────────────────────────────────────────────────

    @Test
    void revokeKey_setsRevoked() {
        insertApiKey(5, "PERSONAL", OWNER, "jti-a", false, FUTURE);
        adminApiKey.revoke(service(), 5);
        assertThat(isKeyRevoked(5)).isTrue();
    }

    @Test
    void revokeKey_unknown_404() {
        ApiException ex = catchThrowableOfType(ApiException.class, () -> adminApiKey.revoke(service(), 999));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ex.getErrorCode()).isEqualTo("KEY_NOT_FOUND");
    }

    @Test
    void revokeKey_requiresServiceKey() {
        insertApiKey(5, "PERSONAL", OWNER, "jti-a", false, FUTURE);
        ApiException ex = catchThrowableOfType(ApiException.class, () -> adminApiKey.revoke(personal(), 5));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(isKeyRevoked(5)).isFalse();
    }

    @Test
    void rotateKey_delegatesToForceRotate_replacesJti() {
        insertApiKey(6, "PERSONAL", OWNER, "jti-old", false, FUTURE);
        adminApiKey.rotate(service(), 6);
        assertThat(jwtIdOf(6)).isNotEqualTo("jti-old");
    }

    @Test
    void rotateKey_requiresServiceKey() {
        insertApiKey(6, "PERSONAL", OWNER, "jti-old", false, FUTURE);
        ApiException ex = catchThrowableOfType(ApiException.class, () -> adminApiKey.rotate(personal(), 6));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jwtIdOf(6)).isEqualTo("jti-old");
    }

    // ── AdminRateLimitService ──────────────────────────────────────────────────────

    @Test
    void setOverride_persistsAndIsReadableByService() {
        adminRateLimit.setOverride(service(), OWNER, new BigDecimal("5.00"), "trusted bot");
        assertThat(rowCount("api_rate_limit_override")).isEqualTo(1);
        assertThat(overrides.getMultiplier(OWNER)).isEqualByComparingTo("5.00");
    }

    @Test
    void setOverride_nonPositiveMultiplier_400() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> adminRateLimit.setOverride(service(), OWNER, new BigDecimal("0"), null));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rowCount("api_rate_limit_override")).isZero();
    }

    @Test
    void setOverride_overlongNote_400() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> adminRateLimit.setOverride(service(), OWNER, new BigDecimal("2"), "n".repeat(256)));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void setOverride_requiresServiceKey() {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> adminRateLimit.setOverride(personal(), OWNER, new BigDecimal("2"), null));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void clearOverride_removesRow() {
        adminRateLimit.setOverride(service(), OWNER, new BigDecimal("3.00"), null);
        adminRateLimit.clearOverride(service(), OWNER);
        assertThat(rowCount("api_rate_limit_override")).isZero();
    }

    @Test
    void clearOverride_requiresServiceKey() {
        adminRateLimit.setOverride(service(), OWNER, new BigDecimal("3.00"), null);
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> adminRateLimit.clearOverride(personal(), OWNER));
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rowCount("api_rate_limit_override")).isEqualTo(1);
    }

    // ── AdminRateLimitService list surface via the override service (findAll) ──────

    @Test
    void listAll_reflectsPersistedOverrides() {
        adminRateLimit.setOverride(service(), OWNER, new BigDecimal("4.00"), "note");
        List<RateLimitOverride> all = overrides.listAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getOwnerUuid()).isEqualTo(OWNER);
        assertThat(all.get(0).getMultiplier()).isEqualByComparingTo("4.00");
    }
}
