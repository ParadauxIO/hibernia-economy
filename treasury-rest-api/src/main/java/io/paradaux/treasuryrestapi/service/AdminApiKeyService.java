package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.mapper.ApiKeyMapper;
import io.paradaux.treasuryrestapi.security.AdminScope;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * SERVICE-scoped admin operations on API keys (ADT-14): revoke and force-rotate.
 * The economy-explorer previously wrote {@code api_keys} directly (and even minted
 * its own JWTs to rotate) — it now routes through here so the key table has one
 * writer and rotation goes through the same path that no longer persists tokens
 * (ADT-6).
 */
@Service
public class AdminApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(AdminApiKeyService.class);

    private final ApiKeyMapper apiKeyMapper;
    private final AuthService authService;

    public AdminApiKeyService(ApiKeyMapper apiKeyMapper, AuthService authService) {
        this.apiKeyMapper = apiKeyMapper;
        this.authService = authService;
    }

    @Transactional
    public void revoke(VerifiedToken verified, long keyId) {
        AdminScope.require(verified);
        if (apiKeyMapper.findByKeyId(keyId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "KEY_NOT_FOUND", "API key not found.");
        }
        apiKeyMapper.revoke(keyId);
        log.info("Admin revoked api keyId={} by keyId={}", keyId, verified.keyId());
    }

    /** Force-rotate: invalidates the current token (jti). Returns the new expiry. */
    public Instant rotate(VerifiedToken verified, long keyId) {
        AdminScope.require(verified);
        return authService.adminForceRotate(keyId);
    }
}
