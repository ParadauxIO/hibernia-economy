package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.CreateWebhookResponse;
import io.paradaux.treasuryrestapi.dto.WebhookResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.mapper.WebhookSubscriptionMapper;
import io.paradaux.treasuryrestapi.model.SubscriptionMatch;
import io.paradaux.treasuryrestapi.model.WebhookSubscription;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for webhook subscription management — scoping, the SSRF gate, the
 * per-key cap, and cross-key isolation. No Spring/DB: the mapper is an in-memory
 * stub. URLs use a literal public IP so the SSRF validator is deterministic offline.
 */
class WebhookServiceTest {

    private static final UUID OWNER = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String URL = "https://93.184.216.34/hook";

    private final StubMapper mapper = new StubMapper();
    private final WebhookService service = new WebhookService(mapper, 2);

    private static VerifiedToken personal(long keyId, long accountId) {
        return new VerifiedToken(keyId, OWNER, "PERSONAL", accountId, null);
    }

    private static VerifiedToken business(long keyId, long firmId) {
        return new VerifiedToken(keyId, OWNER, "BUSINESS", null, firmId);
    }

    @Test
    void createScopesPersonalToAccountAndReturnsSecretOnce() {
        CreateWebhookResponse r = service.create(personal(7, 42), URL);
        assertThat(r.scope()).isEqualTo("account");
        assertThat(r.accountId()).isEqualTo(42L);
        assertThat(r.firmId()).isNull();
        assertThat(r.active()).isTrue();
        assertThat(r.secret()).hasSize(64).matches("[0-9a-f]+");

        // The secret is NOT echoed on subsequent reads.
        WebhookResponse got = service.get(personal(7, 42), r.id());
        assertThat(got.url()).isEqualTo(URL);
    }

    @Test
    void createScopesBusinessToFirm() {
        CreateWebhookResponse r = service.create(business(8, 9), URL);
        assertThat(r.scope()).isEqualTo("firm");
        assertThat(r.firmId()).isEqualTo(9L);
        assertThat(r.accountId()).isNull();
    }

    @Test
    void createRejectsNonPublicUrl() {
        assertThatThrownBy(() -> service.create(personal(7, 42), "https://127.0.0.1/x"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.create(personal(7, 42), "http://93.184.216.34/x"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void enforcesMaxPerKey() {
        service.create(personal(7, 42), URL);
        service.create(personal(7, 42), URL);
        assertThatThrownBy(() -> service.create(personal(7, 42), URL))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void subscriptionsAreIsolatedPerKey() {
        long id = service.create(personal(7, 42), URL).id();
        // A different key cannot see, update, or delete it.
        assertThatThrownBy(() -> service.get(personal(8, 99), id)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.delete(personal(8, 99), id)).isInstanceOf(ApiException.class);
        assertThat(service.list(personal(8, 99))).isEmpty();
        assertThat(service.list(personal(7, 42))).hasSize(1);
    }

    // ── in-memory WebhookSubscriptionMapper stub ─────────────────────────────

    private static final class StubMapper implements WebhookSubscriptionMapper {
        private final Map<Long, WebhookSubscription> rows = new LinkedHashMap<>();
        private long seq = 0;

        @Override public void insert(WebhookSubscription sub) {
            sub.setSubscriptionId(++seq);
            sub.setActive(true);
            rows.put(sub.getSubscriptionId(), sub);
        }
        @Override public WebhookSubscription findById(long id) { return rows.get(id); }
        @Override public List<WebhookSubscription> listByApiKey(long apiKeyId) {
            List<WebhookSubscription> out = new ArrayList<>();
            for (WebhookSubscription s : rows.values()) if (s.getApiKeyId() == apiKeyId) out.add(s);
            return out;
        }
        @Override public int countByApiKey(long apiKeyId) { return listByApiKey(apiKeyId).size(); }
        @Override public int update(long id, String targetUrl, boolean active) {
            WebhookSubscription s = rows.get(id);
            if (s == null) return 0;
            s.setTargetUrl(targetUrl); s.setActive(active);
            return 1;
        }
        @Override public int delete(long id) { return rows.remove(id) != null ? 1 : 0; }
        @Override public int setActiveScoped(long id, java.util.UUID ownerUuid, boolean active) { return 0; }
        @Override public int setUrlScoped(long id, java.util.UUID ownerUuid, String targetUrl) { return 0; }
        @Override public int setSecretScoped(long id, java.util.UUID ownerUuid, String secret) { return 0; }
        @Override public int deleteScoped(long id, java.util.UUID ownerUuid) { return 0; }
        @Override public List<SubscriptionMatch> findAccountMatches(List<Long> accountIds) { return List.of(); }
        @Override public List<SubscriptionMatch> findFirmMatches(List<Long> accountIds) { return List.of(); }
        @Override public int incrementFailures(long id) { return 0; }
        @Override public int resetFailures(long id) { return 0; }
        @Override public int disableIfOverThreshold(long id, long threshold) { return 0; }
    }
}
