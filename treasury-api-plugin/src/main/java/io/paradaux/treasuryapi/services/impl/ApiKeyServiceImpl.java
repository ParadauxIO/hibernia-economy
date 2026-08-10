package io.paradaux.treasuryapi.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.jsonwebtoken.Jwts;
import io.paradaux.business.api.BusinessApi;
import io.paradaux.common.JwtKeys;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.treasuryapi.mappers.ApiKeyMapper;
import io.paradaux.treasuryapi.model.config.ApiConfiguration;
import io.paradaux.treasuryapi.model.economy.ApiKey;
import io.paradaux.treasuryapi.model.economy.KeyType;
import io.paradaux.treasuryapi.services.ApiKeyService;
import org.mybatis.guice.transactional.Transactional;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Singleton
public class ApiKeyServiceImpl implements ApiKeyService {

    private static final long KEY_LIFETIME_DAYS = 180;

    private final ApiKeyMapper apiKeyMapper;
    private final ApiConfiguration apiConfig;
    private final BusinessApi businessApi;

    @Inject
    public ApiKeyServiceImpl(ApiKeyMapper apiKeyMapper, ApiConfiguration apiConfig, BusinessApi businessApi) {
        this.apiKeyMapper = apiKeyMapper;
        this.apiConfig = apiConfig;
        this.businessApi = businessApi;
    }

    @Override
    @Transactional
    public ApiKey issuePersonalKey(int accountId, UUID ownerUuid) {
        return issue(KeyType.PERSONAL, accountId, null, ownerUuid);
    }

    @Override
    @Transactional
    public ApiKey issueBusinessKey(int firmId, UUID ownerUuid) {
        return issue(KeyType.BUSINESS, null, firmId, ownerUuid);
    }

    private ApiKey issue(KeyType keyType, Integer accountId, Integer firmId, UUID ownerUuid) {
        String jwtId = UUID.randomUUID().toString();
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(KEY_LIFETIME_DAYS, ChronoUnit.DAYS);

        ApiKey key = new ApiKey();
        key.setKeyType(keyType);
        key.setAccountId(accountId);
        key.setFirmId(firmId);
        key.setOwnerUuid(ownerUuid);
        key.setJwtId(jwtId);
        key.setIssuedAt(issuedAt);
        key.setExpiresAt(expiresAt);

        // Insert (no token column) to obtain the generated keyId.
        apiKeyMapper.insert(key);

        // Build the real JWT now that we have the keyId. It is set on the returned
        // object for one-time display to the issuer and is NEVER persisted (ADT-6).
        String token = buildJwt(key.getKeyId(), jwtId, keyType, accountId, firmId, ownerUuid, issuedAt, expiresAt);
        key.setToken(token);

        return key;
    }

    @Override
    @Transactional
    public ApiKey reissueKey(int keyId, UUID actingUuid) {
        ApiKey existing = apiKeyMapper.findById(keyId);
        if (existing == null) {
            throw new NotFoundException("treasuryapi.key.not-found");
        }
        // The service is the authorization boundary (plugin-architecture/0005): the
        // ownership/proprietorship check holds regardless of caller, so a handler that
        // forgot to pre-check (or a future non-command caller) still can't rotate a key
        // they don't control.
        requireCanManage(existing, actingUuid);
        // Revocation is terminal (ADT-110): a revoked key must not be resurrected
        // by reissuing it. Reject early so the caller gets a clear error and we
        // never mint a fresh token for a credential that was deliberately killed
        // (e.g. after a leak). Issue a new key instead.
        if (existing.isRevoked()) {
            throw new ConflictException("treasuryapi.key.revoked");
        }

        String newJwtId = UUID.randomUUID().toString();
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(KEY_LIFETIME_DAYS, ChronoUnit.DAYS);
        String token = buildJwt(keyId, newJwtId, existing.getKeyType(),
                existing.getAccountId(), existing.getFirmId(),
                existing.getOwnerUuid(), issuedAt, expiresAt);

        // The mapper's UPDATE is guarded by `AND revoked = 0`, so a key revoked
        // between the read above and this write affects 0 rows — treat that as a
        // terminal-state rejection rather than silently building an unusable token.
        int updated = apiKeyMapper.reissue(keyId, newJwtId, issuedAt, expiresAt);
        if (updated == 0) {
            throw new ConflictException("treasuryapi.key.revoked");
        }

        existing.setJwtId(newJwtId);
        existing.setToken(token); // one-time display only; not persisted (ADT-6)
        existing.setIssuedAt(issuedAt);
        existing.setExpiresAt(expiresAt);
        return existing;
    }

    @Override
    @Transactional
    public void revokeKey(int keyId, UUID actingUuid) {
        ApiKey existing = apiKeyMapper.findById(keyId);
        if (existing == null) {
            throw new NotFoundException("treasuryapi.key.not-found");
        }
        requireCanManage(existing, actingUuid);
        apiKeyMapper.revoke(keyId);
    }

    /**
     * Enforces the per-key management invariant: a PERSONAL key may be managed only by
     * its current owner; a BUSINESS key only by a CURRENT proprietor of its firm — never
     * the individual who happened to issue it (ADT-111), so the firm keeps control of its
     * own credential after a proprietor transfer. Throws {@code NoPermissionException}
     * on failure.
     */
    private void requireCanManage(ApiKey key, UUID actingUuid) {
        boolean allowed = switch (key.getKeyType()) {
            case BUSINESS -> key.getFirmId() != null
                    && businessApi.firms().isProprietor(key.getFirmId(), actingUuid);
            case PERSONAL, GOVERNMENT -> key.getOwnerUuid() != null
                    && key.getOwnerUuid().equals(actingUuid);
        };
        if (!allowed) {
            throw new NoPermissionException("treasuryapi.key.no-access");
        }
    }

    @Override
    public ApiKey getKey(int keyId) {
        return apiKeyMapper.findById(keyId);
    }

    @Override
    public List<ApiKey> listKeys(UUID ownerUuid, KeyType keyType) {
        return apiKeyMapper.findByOwnerAndType(ownerUuid, keyType);
    }

    @Override
    public List<ApiKey> listBusinessKeysAccessibleByEmployee(UUID employeeUuid) {
        return apiKeyMapper.findBusinessAccessibleByEmployee(employeeUuid);
    }

    private String buildJwt(int keyId, String jwtId, KeyType keyType,
                             Integer accountId, Integer firmId, UUID ownerUuid,
                             Instant issuedAt, Instant expiresAt) {
        SecretKey key = JwtKeys.deriveHmacKey(apiConfig.getJwtSecret());
        var builder = Jwts.builder()
                .header().add("kid", String.valueOf(keyId)).and()
                .subject(ownerUuid.toString())
                .claim("type", keyType.name())
                .id(jwtId)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt));

        if (accountId != null) builder.claim("acc", accountId);
        if (firmId != null)    builder.claim("firm", firmId);

        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }

}
