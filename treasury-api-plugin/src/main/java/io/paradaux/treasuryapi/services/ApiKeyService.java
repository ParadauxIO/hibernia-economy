package io.paradaux.treasuryapi.services;

import io.paradaux.treasuryapi.model.economy.ApiKey;
import io.paradaux.treasuryapi.model.economy.KeyType;

import java.util.List;
import java.util.UUID;

public interface ApiKeyService {
    ApiKey issuePersonalKey(int accountId, UUID ownerUuid);
    ApiKey issueBusinessKey(int firmId, UUID ownerUuid);

    /**
     * Reissues (rotates) the key on behalf of {@code actingUuid}. The service is the
     * authoritative authorization boundary (treasury-api-plugin/plugin-architecture/0005):
     * it enforces that the acting player may manage the key — the current owner of a
     * PERSONAL key, or a current proprietor of a BUSINESS key's firm — regardless of
     * caller. Throws {@code NotFoundException} if no such key exists,
     * {@code NoPermissionException} if the acting player may not manage it, and
     * {@code ConflictException} if the key is revoked (revocation is terminal).
     */
    ApiKey reissueKey(int keyId, UUID actingUuid);

    /**
     * Revokes the key on behalf of {@code actingUuid}. Same authorization invariant as
     * {@link #reissueKey(int, UUID)}: throws {@code NotFoundException} if absent and
     * {@code NoPermissionException} if the acting player may not manage the key.
     */
    void revokeKey(int keyId, UUID actingUuid);

    /** Looks up a single key by id, or {@code null} if none exists. */
    ApiKey getKey(int keyId);

    /** Lists the keys of the given type owned by the player, ordered by key id. */
    List<ApiKey> listKeys(UUID ownerUuid, KeyType keyType);

    /** Lists BUSINESS keys for every firm the player is currently employed at. */
    List<ApiKey> listBusinessKeysAccessibleByEmployee(UUID employeeUuid);
}
