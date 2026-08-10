package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.CreateWebhookResponse;
import io.paradaux.treasuryrestapi.dto.WebhookResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.mapper.WebhookSubscriptionMapper;
import io.paradaux.treasuryrestapi.model.WebhookSubscription;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.util.Hex;
import io.paradaux.treasuryrestapi.util.SsrfValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Self-service management of webhook subscriptions, scoped to the calling API
 * key ({@link VerifiedToken#keyId()}). PERSONAL/GOVERNMENT keys subscribe their
 * own account; BUSINESS keys subscribe their firm (all its accounts). Players
 * supply the URL, so it is SSRF-validated on create/update.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebhookSubscriptionMapper mapper;
    private final int maxPerKey;

    public WebhookService(WebhookSubscriptionMapper mapper,
                          @Value("${treasury.webhook.max-per-key:20}") int maxPerKey) {
        this.mapper = mapper;
        this.maxPerKey = maxPerKey;
    }

    public CreateWebhookResponse create(VerifiedToken verified, String url) {
        SsrfValidator.validate(url);

        if (mapper.countByApiKey(verified.keyId()) >= maxPerKey) {
            throw new ApiException(HttpStatus.CONFLICT, "WEBHOOK_LIMIT",
                    "This API key already has the maximum of " + maxPerKey + " webhooks.");
        }

        WebhookSubscription sub = new WebhookSubscription();
        sub.setApiKeyId(verified.keyId());
        sub.setOwnerUuid(verified.ownerUuid());
        sub.setKeyType(verified.keyType());
        if ("BUSINESS".equals(verified.keyType())) {
            if (verified.firmId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_KEY", "BUSINESS key has no firm scope.");
            }
            sub.setFirmId(verified.firmId().intValue());
        } else {
            if (verified.accountId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_KEY", "Key has no account scope.");
            }
            sub.setAccountId(verified.accountId().intValue());
        }
        String secret = newSecret();
        sub.setTargetUrl(url.trim());
        sub.setSecret(secret);

        mapper.insert(sub);
        log.info("Webhook created id={} keyId={} scope={} url={}",
                sub.getSubscriptionId(), verified.keyId(),
                sub.getFirmId() != null ? "firm:" + sub.getFirmId() : "account:" + sub.getAccountId(), url);

        return new CreateWebhookResponse(sub.getSubscriptionId(),
                sub.getFirmId() != null ? "firm" : "account",
                sub.getAccountId() != null ? sub.getAccountId().longValue() : null,
                sub.getFirmId() != null ? sub.getFirmId().longValue() : null,
                sub.getTargetUrl(), true, secret);
    }

    public List<WebhookResponse> list(VerifiedToken verified) {
        return mapper.listByApiKey(verified.keyId()).stream().map(WebhookService::toResponse).toList();
    }

    public WebhookResponse get(VerifiedToken verified, long id) {
        return toResponse(requireOwned(verified, id));
    }

    public WebhookResponse update(VerifiedToken verified, long id, String url, Boolean active) {
        WebhookSubscription sub = requireOwned(verified, id);
        String newUrl = sub.getTargetUrl();
        if (url != null) {
            SsrfValidator.validate(url);
            newUrl = url.trim();
        }
        boolean newActive = active != null ? active : sub.isActive();
        mapper.update(id, newUrl, newActive);
        log.info("Webhook updated id={} keyId={} active={} urlChanged={}", id, verified.keyId(), newActive, url != null);
        return toResponse(mapper.findById(id));
    }

    public void delete(VerifiedToken verified, long id) {
        requireOwned(verified, id);
        mapper.delete(id);
        log.info("Webhook deleted id={} keyId={}", id, verified.keyId());
    }

    /**
     * Loads the subscription and asserts it belongs to the calling key (404 otherwise).
     * Owner-scoped rows (null api_key_id, managed via economy-explorer) are never
     * owned by an API key, so the REST API can't manage them — that's intended.
     */
    private WebhookSubscription requireOwned(VerifiedToken verified, long id) {
        WebhookSubscription sub = mapper.findById(id);
        if (sub == null || sub.getApiKeyId() == null || sub.getApiKeyId() != verified.keyId()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WEBHOOK_NOT_FOUND", "Webhook not found.");
        }
        return sub;
    }

    private static WebhookResponse toResponse(WebhookSubscription s) {
        return new WebhookResponse(s.getSubscriptionId(),
                s.getFirmId() != null ? "firm" : "account",
                s.getAccountId() != null ? s.getAccountId().longValue() : null,
                s.getFirmId() != null ? s.getFirmId().longValue() : null,
                s.getTargetUrl(), s.isActive(), s.getConsecutiveFailures(),
                s.getCreatedAt() != null ? s.getCreatedAt().toInstant(ZoneOffset.UTC) : null);
    }

    private static String newSecret() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return Hex.encode(buf);
    }
}
