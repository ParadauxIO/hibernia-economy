package io.paradaux.treasuryapi.services.impl;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.paradaux.business.api.BusinessApi;
import io.paradaux.business.api.FirmApi;
import io.paradaux.common.JwtKeys;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.treasuryapi.mappers.ApiKeyMapper;
import io.paradaux.treasuryapi.model.config.ApiConfiguration;
import io.paradaux.treasuryapi.model.economy.ApiKey;
import io.paradaux.treasuryapi.model.economy.KeyType;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the security-critical JWT mint + key-derivation path (ADT-50): a minted
 * token must verify under the key derived from the configured secret and carry
 * the correct claims. A regression in signing or derivation would otherwise ship
 * silently — these keys are the credential for the whole REST surface.
 *
 * <p>Also pins the service-side authorization boundary (plugin-architecture/0005):
 * reissue/revoke enforce ownership/proprietorship regardless of caller.
 */
class ApiKeyServiceImplTest {

    private static final String SECRET = "test-secret-please-use-32-plus-characters-xx";

    private final ApiConfiguration config = new ApiConfiguration() {
        @Override public String getJwtSecret() { return SECRET; }
    };

    private final BusinessApi businessApi = mock(BusinessApi.class);

    private ApiKeyServiceImpl service(ApiKeyMapper mapper) {
        return new ApiKeyServiceImpl(mapper, config, businessApi);
    }

    /** In-memory ApiKeyMapper: insert assigns a key id the way the DB would. */
    private static ApiKeyMapper mapper(int generatedId) {
        return new ApiKeyMapper() {
            @Override public void insert(ApiKey k) { k.setKeyId(generatedId); }
            @Override public ApiKey findById(int keyId) { return null; }
            @Override public int reissue(int keyId, String jwtId, Instant issuedAt, Instant expiresAt) { return 1; }
            @Override public int revoke(int keyId) { return 0; }
            @Override public List<ApiKey> findByOwnerAndType(UUID ownerUuid, KeyType keyType) { return List.of(); }
            @Override public List<ApiKey> findBusinessAccessibleByEmployee(UUID playerUuid) { return List.of(); }
        };
    }

    @Test
    void issuePersonalKey_mintsTokenVerifiableWithDerivedKey_carryingClaims() {
        UUID owner = UUID.randomUUID();
        ApiKey key = service(mapper(42)).issuePersonalKey(7, owner);

        assertNotNull(key.getToken(), "mint must return the one-time token");
        assertNotNull(key.getJwtId());
        assertEquals(42, key.getKeyId());

        Jws<Claims> jws = Jwts.parser().verifyWith(deriveKey(SECRET)).build()
                .parseSignedClaims(key.getToken());
        Claims claims = jws.getPayload();
        assertEquals("PERSONAL", claims.get("type", String.class));
        assertEquals(7, claims.get("acc", Integer.class));
        assertNull(claims.get("firm"), "personal key must not carry a firm claim");
        assertEquals(owner.toString(), claims.getSubject());
        assertEquals(key.getJwtId(), claims.getId(), "jti claim must match the stored jwt_id");
        assertEquals("42", jws.getHeader().get("kid"), "kid header must be the key id");
    }

    @Test
    void issueBusinessKey_carriesFirmClaim_notAccount() {
        ApiKey key = service(mapper(9)).issueBusinessKey(3, UUID.randomUUID());

        Claims claims = Jwts.parser().verifyWith(deriveKey(SECRET)).build()
                .parseSignedClaims(key.getToken()).getPayload();
        assertEquals("BUSINESS", claims.get("type", String.class));
        assertEquals(3, claims.get("firm", Integer.class));
        assertNull(claims.get("acc"), "business key must not carry an account claim");
    }

    @Test
    void mintedTokenFailsVerificationUnderADifferentSecret() {
        ApiKey key = service(mapper(1)).issuePersonalKey(1, UUID.randomUUID());
        assertThrows(Exception.class, () ->
                Jwts.parser().verifyWith(deriveKey("a-totally-different-secret-32-plus-chars"))
                        .build().parseSignedClaims(key.getToken()));
    }

    @Test
    void reissueKey_rejectsRevokedKey_revocationIsTerminal() {
        // ADT-110: a deliberately-revoked credential must not be resurrected by reissue.
        UUID owner = UUID.randomUUID();
        ApiKey revoked = new ApiKey();
        revoked.setKeyId(5);
        revoked.setKeyType(KeyType.PERSONAL);
        revoked.setOwnerUuid(owner);
        revoked.setRevoked(true);
        assertThrows(ConflictException.class,
                () -> new ApiKeyServiceImpl(mapperReturning(revoked, 1), config, businessApi).reissueKey(5, owner));
    }

    @Test
    void reissueKey_rejectsWhenUpdateAffectsNoRows_revokedBetweenReadAndWrite() {
        // The mapper's `AND revoked = 0` guard yields 0 rows if the key was revoked
        // after the read; the service must reject rather than mint an unusable token.
        UUID owner = UUID.randomUUID();
        ApiKey active = new ApiKey();
        active.setKeyId(6);
        active.setKeyType(KeyType.PERSONAL);
        active.setOwnerUuid(owner);
        active.setRevoked(false);
        assertThrows(ConflictException.class,
                () -> new ApiKeyServiceImpl(mapperReturning(active, 0), config, businessApi).reissueKey(6, owner));
    }

    // ---- Service-side authorization boundary (plugin-architecture/0005) ----
    // These pin that reissue/revoke enforce ownership (PERSONAL) / proprietorship
    // (BUSINESS) in the SERVICE, regardless of caller — so removing the check makes
    // them fail even though the command handler still pre-checks.

    @Test
    void reissueKey_personal_nonOwner_isRejectedByService_neverWritesReissue() {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        ApiKey active = personalKey(1, owner);
        ApiKeyMapper mapper = trackingMapper(active);

        assertThrows(NoPermissionException.class,
                () -> new ApiKeyServiceImpl(mapper, config, businessApi).reissueKey(1, intruder));
        verify(mapper, never()).reissue(anyInt(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void revokeKey_personal_nonOwner_isRejectedByService_neverRevokes() {
        UUID owner = UUID.randomUUID();
        UUID intruder = UUID.randomUUID();
        ApiKeyMapper mapper = trackingMapper(personalKey(1, owner));

        assertThrows(NoPermissionException.class,
                () -> new ApiKeyServiceImpl(mapper, config, businessApi).revokeKey(1, intruder));
        verify(mapper, never()).revoke(anyInt());
    }

    @Test
    void revokeKey_personal_owner_revokes() {
        UUID owner = UUID.randomUUID();
        ApiKeyMapper mapper = trackingMapper(personalKey(1, owner));

        new ApiKeyServiceImpl(mapper, config, businessApi).revokeKey(1, owner);
        verify(mapper).revoke(1);
    }

    @Test
    void reissueKey_business_nonProprietor_isRejectedByService_neverWritesReissue() {
        UUID actor = UUID.randomUUID();
        ApiKey biz = businessKey(1, 7);
        FirmApi firms = mock(FirmApi.class);
        when(businessApi.firms()).thenReturn(firms);
        when(firms.isProprietor(7, actor)).thenReturn(false);
        ApiKeyMapper mapper = trackingMapper(biz);

        assertThrows(NoPermissionException.class,
                () -> new ApiKeyServiceImpl(mapper, config, businessApi).reissueKey(1, actor));
        verify(mapper, never()).reissue(anyInt(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void revokeKey_business_currentProprietor_revokes() {
        UUID actor = UUID.randomUUID();
        FirmApi firms = mock(FirmApi.class);
        when(businessApi.firms()).thenReturn(firms);
        when(firms.isProprietor(7, actor)).thenReturn(true);
        ApiKeyMapper mapper = trackingMapper(businessKey(1, 7));

        new ApiKeyServiceImpl(mapper, config, businessApi).revokeKey(1, actor);
        verify(mapper).revoke(1);
    }

    @Test
    void reissueKey_missingKey_throwsNotFound() {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        when(mapper.findById(9)).thenReturn(null);
        assertThrows(NotFoundException.class,
                () -> new ApiKeyServiceImpl(mapper, config, businessApi).reissueKey(9, UUID.randomUUID()));
    }

    private static ApiKey personalKey(int keyId, UUID owner) {
        ApiKey k = new ApiKey();
        k.setKeyId(keyId);
        k.setKeyType(KeyType.PERSONAL);
        k.setOwnerUuid(owner);
        k.setRevoked(false);
        return k;
    }

    private static ApiKey businessKey(int keyId, int firmId) {
        ApiKey k = new ApiKey();
        k.setKeyId(keyId);
        k.setKeyType(KeyType.BUSINESS);
        k.setFirmId(firmId);
        k.setRevoked(false);
        return k;
    }

    /** A Mockito mapper whose findById returns {@code found}, so `never()` verifications work. */
    private static ApiKeyMapper trackingMapper(ApiKey found) {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        when(mapper.findById(found.getKeyId())).thenReturn(found);
        when(mapper.reissue(anyInt(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(1);
        return mapper;
    }

    /** In-memory mapper whose findById returns {@code found} and reissue affects {@code rows}. */
    private static ApiKeyMapper mapperReturning(ApiKey found, int rows) {
        return new ApiKeyMapper() {
            @Override public void insert(ApiKey k) {}
            @Override public ApiKey findById(int keyId) { return found; }
            @Override public int reissue(int keyId, String jwtId, Instant issuedAt, Instant expiresAt) { return rows; }
            @Override public int revoke(int keyId) { return 1; }
            @Override public List<ApiKey> findByOwnerAndType(UUID ownerUuid, KeyType keyType) { return List.of(); }
            @Override public List<ApiKey> findBusinessAccessibleByEmployee(UUID playerUuid) { return List.of(); }
        };
    }

    @Test
    void mintWithBase64Secret_verifiesUnderTheSharedDerivation() {
        // The production-recommended secret format is base64:<random>; exercise it
        // through the real JwtKeys derivation (ADT test-bypasses-jwtkeys-derivation)
        // so a regression in the base64 path can't pass while breaking minted tokens.
        String base64Secret = "base64:" + Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)); // 32 bytes
        ApiConfiguration base64Config = new ApiConfiguration() {
            @Override public String getJwtSecret() { return base64Secret; }
        };
        ApiKey key = new ApiKeyServiceImpl(mapper(11), base64Config, businessApi).issuePersonalKey(4, UUID.randomUUID());
        Claims claims = Jwts.parser().verifyWith(deriveKey(base64Secret)).build()
                .parseSignedClaims(key.getToken()).getPayload();
        assertEquals("PERSONAL", claims.get("type", String.class));
        assertEquals(4, claims.get("acc", Integer.class));
    }

    /** The real production derivation — both mint (here) and the REST verifier use io.paradaux.common.JwtKeys. */
    private static SecretKey deriveKey(String secret) {
        return JwtKeys.deriveHmacKey(secret);
    }

    // ---- Read pass-through coverage (ADT treasury-api-plugin/testing/0007) ----
    // These service methods carry no logic beyond routing to the correct mapper
    // query; the risk is a mis-wire (e.g. listKeys hitting the business-accessible
    // query, or the wrong type). Each test pins the exact mapper call so a
    // regression that swaps the delegation target fails here.

    @Test
    void getKey_returnsMapperRowById() {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        ApiKey found = new ApiKey();
        found.setKeyId(88);
        when(mapper.findById(88)).thenReturn(found);

        ApiKey result = service(mapper).getKey(88);

        assertSame(found, result, "getKey must return the mapper's findById row unchanged");
        verify(mapper).findById(88);
    }

    @Test
    void getKey_missing_returnsNull() {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        when(mapper.findById(99)).thenReturn(null);
        assertNull(service(mapper).getKey(99));
    }

    @Test
    void listKeys_queriesByOwnerAndType_notTheEmployeeAccessQuery() {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        UUID owner = UUID.randomUUID();
        ApiKey a = new ApiKey();
        when(mapper.findByOwnerAndType(owner, KeyType.PERSONAL)).thenReturn(List.of(a));

        List<ApiKey> result = service(mapper).listKeys(owner, KeyType.PERSONAL);

        assertEquals(List.of(a), result);
        // Must route to the owner/type query with the caller's exact type —
        // never the employee-access query.
        verify(mapper).findByOwnerAndType(owner, KeyType.PERSONAL);
        verifyNoMoreInteractions(mapper);
    }

    @Test
    void listBusinessKeysAccessibleByEmployee_queriesTheEmployeeAccessJoin() {
        ApiKeyMapper mapper = mock(ApiKeyMapper.class);
        UUID employee = UUID.randomUUID();
        ApiKey a = new ApiKey();
        when(mapper.findBusinessAccessibleByEmployee(employee)).thenReturn(List.of(a));

        List<ApiKey> result = service(mapper).listBusinessKeysAccessibleByEmployee(employee);

        assertEquals(List.of(a), result);
        verify(mapper).findBusinessAccessibleByEmployee(employee);
        verifyNoMoreInteractions(mapper);
    }
}
