package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.WebhookCreateRequest;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.mapper.WebhookSubscriptionMapper;
import io.paradaux.treasuryrestapi.model.WebhookSubscription;
import io.paradaux.treasuryrestapi.security.AdminScope;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.util.SsrfValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * SERVICE-scoped admin management of webhook subscriptions (ADT-14). The
 * economy-explorer previously wrote {@code webhook_subscription} directly (both
 * owner self-service and fleet-wide admin) — those writes now route through here,
 * so the table has one writer and the SSRF check lives on the authoritative side.
 *
 * <p>{@code ownerUuid} is the optional owner scope: when present the mutator only
 * affects a row owned by that UUID (player self-service); when null it's an admin
 * fleet operation by id. All mutators return the rows affected so the caller can
 * detect a not-found / not-owned subscription.
 */
@Service
public class AdminWebhookService {

    private static final Logger log = LoggerFactory.getLogger(AdminWebhookService.class);

    // webhook_subscription.secret is CHAR(64) (the hex HMAC key). Validate length
    // up front so an over-long value returns a clean 400 rather than a driver-level
    // data-truncation error surfaced as a 500 (ADT-118).
    private static final int MAX_SECRET_LENGTH = 64;

    // webhook_subscription.key_type is ENUM('PERSONAL','BUSINESS','GOVERNMENT') NOT NULL.
    // The DTO carries it as a free @NotBlank String, so validate against the enum values
    // up front: anything else (e.g. 'SYSTEM', 'SERVICE', typos) would otherwise truncate
    // to '' or error at the driver, surfacing as a 500 instead of a clean 400. key_type is
    // delivery metadata only (the dispatcher matches on account_id/firm_id), so this is
    // purely input hygiene, not a routing decision (ADT-118).
    private static final Set<String> VALID_KEY_TYPES = Set.of("PERSONAL", "BUSINESS", "GOVERNMENT");

    private final WebhookSubscriptionMapper mapper;

    public AdminWebhookService(WebhookSubscriptionMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public long create(VerifiedToken verified, WebhookCreateRequest req) {
        AdminScope.require(verified);
        SsrfValidator.validate(req.targetUrl()); // https, public address, standard port
        validateSecret(req.secret());
        validateKeyType(req.keyType());
        WebhookSubscription sub = new WebhookSubscription();
        sub.setApiKeyId(null); // owner-scoped (explorer-managed), not key-scoped
        sub.setOwnerUuid(req.ownerUuid());
        sub.setKeyType(req.keyType());
        sub.setAccountId(req.accountId());
        sub.setFirmId(req.firmId());
        sub.setTargetUrl(req.targetUrl().trim());
        sub.setSecret(req.secret());
        mapper.insert(sub);
        log.info("Admin created webhook id={} for owner={} by keyId={}",
                sub.getSubscriptionId(), req.ownerUuid(), verified.keyId());
        return sub.getSubscriptionId();
    }

    public int setActive(VerifiedToken verified, long id, UUID ownerUuid, boolean active) {
        AdminScope.require(verified);
        return mapper.setActiveScoped(id, ownerUuid, active);
    }

    public int setUrl(VerifiedToken verified, long id, UUID ownerUuid, String targetUrl) {
        AdminScope.require(verified);
        SsrfValidator.validate(targetUrl);
        return mapper.setUrlScoped(id, ownerUuid, targetUrl.trim());
    }

    public int setSecret(VerifiedToken verified, long id, UUID ownerUuid, String secret) {
        AdminScope.require(verified);
        validateSecret(secret);
        return mapper.setSecretScoped(id, ownerUuid, secret);
    }

    private static void validateSecret(String secret) {
        if (secret != null && secret.length() > MAX_SECRET_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "Field 'secret' must be at most " + MAX_SECRET_LENGTH + " characters.");
        }
    }

    private static void validateKeyType(String keyType) {
        if (keyType == null || !VALID_KEY_TYPES.contains(keyType)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "Field 'keyType' must be one of PERSONAL, BUSINESS, GOVERNMENT.");
        }
    }

    public int delete(VerifiedToken verified, long id, UUID ownerUuid) {
        AdminScope.require(verified);
        return mapper.deleteScoped(id, ownerUuid);
    }
}
