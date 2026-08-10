package io.paradaux.treasuryapi.commands;

import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasuryapi.model.economy.ApiKey;
import io.paradaux.treasuryapi.model.economy.KeyType;
import io.paradaux.treasuryapi.services.ApiKeyService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the owner-UUID equality authorization on {@link PersonalKeyHandler}: a
 * PERSONAL key may only be reissued/revoked by the player who owns it. Each guard
 * gets both the negative (a different player is rejected and the mutating service
 * call never fires) and positive (the owner succeeds) case, so deleting the
 * {@code getOwnerUuid().equals(...)} check would fail these.
 */
class PersonalKeyHandlerTest {

    private ApiKeyService apiKeyService;
    private TreasuryApi treasuryApi;
    private Message message;
    private PersonalKeyHandler handler;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID intruderId = UUID.randomUUID();
    private Player owner;
    private Player intruder;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        treasuryApi = mock(TreasuryApi.class);
        message = mock(Message.class);
        handler = new PersonalKeyHandler(apiKeyService, treasuryApi, message);

        owner = mock(Player.class);
        when(owner.getUniqueId()).thenReturn(ownerId);
        intruder = mock(Player.class);
        when(intruder.getUniqueId()).thenReturn(intruderId);
    }

    private ApiKey personalKey(UUID keyOwner, boolean revoked) {
        ApiKey key = new ApiKey();
        key.setKeyId(11);
        key.setKeyType(KeyType.PERSONAL);
        key.setOwnerUuid(keyOwner);
        key.setRevoked(revoked);
        key.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        return key;
    }

    @Test
    void doReissue_nonOwner_isRejected_andNeverReissues() {
        when(apiKeyService.getKey(11)).thenReturn(personalKey(ownerId, false));

        handler.doReissue(intruder, 11);

        verify(message).send(intruder, "treasuryapi.personal.reissue.no-access");
        verify(apiKeyService, never()).reissueKey(anyInt(), any());
    }

    @Test
    void doReissue_owner_reissues() {
        when(apiKeyService.getKey(11)).thenReturn(personalKey(ownerId, false));
        ApiKey rotated = personalKey(ownerId, false);
        rotated.setToken("new-token");
        when(apiKeyService.reissueKey(11, ownerId)).thenReturn(rotated);

        handler.doReissue(owner, 11);

        verify(apiKeyService).reissueKey(11, ownerId);
        verify(message).send(owner, "treasuryapi.personal.reissue.success",
                "keyId", "11", "token", "new-token");
    }

    @Test
    void doRevoke_nonOwner_isRejected_andNeverRevokes() {
        when(apiKeyService.getKey(11)).thenReturn(personalKey(ownerId, false));

        handler.doRevoke(intruder, 11);

        verify(message).send(intruder, "treasuryapi.personal.revoke.no-access");
        verify(apiKeyService, never()).revokeKey(anyInt(), any());
    }

    @Test
    void doRevoke_owner_revokes() {
        when(apiKeyService.getKey(11)).thenReturn(personalKey(ownerId, false));

        handler.doRevoke(owner, 11);

        verify(apiKeyService).revokeKey(11, ownerId);
        verify(message).send(owner, "treasuryapi.personal.revoke.success", "keyId", "11");
    }

    @Test
    void doReissue_wrongKeyType_isNotFound_neverChecksOwnership() {
        ApiKey business = personalKey(ownerId, false);
        business.setKeyType(KeyType.BUSINESS);
        when(apiKeyService.getKey(11)).thenReturn(business);

        // Even the owner must be bounced when the key isn't a PERSONAL key —
        // PersonalKeyHandler only manages PERSONAL keys.
        handler.doReissue(owner, 11);

        verify(message).send(owner, "treasuryapi.personal.reissue.not-found");
        verify(apiKeyService, never()).reissueKey(anyInt(), any());
    }

    @Test
    void doReissue_revokedKey_ownerToldTerminal_neverReissues() {
        when(apiKeyService.getKey(11)).thenReturn(personalKey(ownerId, true));

        handler.doReissue(owner, 11);

        verify(message).send(owner, "treasuryapi.personal.reissue.revoked");
        verify(apiKeyService, never()).reissueKey(anyInt(), any());
    }
}
