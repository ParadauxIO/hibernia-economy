package io.paradaux.treasuryapi.commands;

import io.paradaux.business.api.BusinessApi;
import io.paradaux.business.api.FirmApi;
import io.paradaux.hibernia.framework.i18n.Message;
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
 * Pins the ADT-111 {@code canManage} authorization on {@link BusinessKeyHandler}:
 * reissue/revoke of a firm-scoped BUSINESS key is gated on CURRENT proprietorship
 * of the key's firm — never on the individual who issued it. Both the negative
 * (a non-proprietor is rejected and the mutating service call never fires) and
 * positive (a current proprietor succeeds) case are covered, so deleting the
 * {@code isProprietor(key.getFirmId(), ...)} gate would fail these.
 */
class BusinessKeyHandlerTest {

    private ApiKeyService apiKeyService;
    private BusinessApi businessApi;
    private FirmApi firms;
    private Message message;
    private BusinessKeyHandler handler;

    private final UUID proprietorId = UUID.randomUUID();
    private final UUID outsiderId = UUID.randomUUID();
    private Player proprietor;
    private Player outsider;

    @BeforeEach
    void setUp() {
        apiKeyService = mock(ApiKeyService.class);
        businessApi = mock(BusinessApi.class);
        firms = mock(FirmApi.class);
        when(businessApi.firms()).thenReturn(firms);
        message = mock(Message.class);
        handler = new BusinessKeyHandler(apiKeyService, businessApi, message);

        proprietor = mock(Player.class);
        when(proprietor.getUniqueId()).thenReturn(proprietorId);
        outsider = mock(Player.class);
        when(outsider.getUniqueId()).thenReturn(outsiderId);
    }

    private ApiKey businessKey(Integer firmId, boolean revoked) {
        ApiKey key = new ApiKey();
        key.setKeyId(21);
        key.setKeyType(KeyType.BUSINESS);
        key.setFirmId(firmId);
        key.setRevoked(revoked);
        key.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        return key;
    }

    @Test
    void doReissue_nonProprietor_isRejected_andNeverReissues() {
        when(apiKeyService.getKey(21)).thenReturn(businessKey(7, false));
        when(firms.isProprietor(7, outsiderId)).thenReturn(false);

        handler.doReissue(outsider, 21);

        verify(message).send(outsider, "treasuryapi.business.reissue.no-access");
        verify(apiKeyService, never()).reissueKey(anyInt(), any());
    }

    @Test
    void doReissue_currentProprietor_reissues() {
        when(apiKeyService.getKey(21)).thenReturn(businessKey(7, false));
        when(firms.isProprietor(7, proprietorId)).thenReturn(true);
        ApiKey rotated = businessKey(7, false);
        rotated.setToken("biz-token");
        when(apiKeyService.reissueKey(21, proprietorId)).thenReturn(rotated);

        handler.doReissue(proprietor, 21);

        verify(apiKeyService).reissueKey(21, proprietorId);
        verify(message).send(proprietor, "treasuryapi.business.reissue.success",
                "keyId", "21", "token", "biz-token");
    }

    @Test
    void doRevoke_nonProprietor_isRejected_andNeverRevokes() {
        when(apiKeyService.getKey(21)).thenReturn(businessKey(7, false));
        when(firms.isProprietor(7, outsiderId)).thenReturn(false);

        handler.doRevoke(outsider, 21);

        verify(message).send(outsider, "treasuryapi.business.revoke.no-access");
        verify(apiKeyService, never()).revokeKey(anyInt(), any());
    }

    @Test
    void doRevoke_currentProprietor_revokes() {
        when(apiKeyService.getKey(21)).thenReturn(businessKey(7, false));
        when(firms.isProprietor(7, proprietorId)).thenReturn(true);

        handler.doRevoke(proprietor, 21);

        verify(apiKeyService).revokeKey(21, proprietorId);
        verify(message).send(proprietor, "treasuryapi.business.revoke.success", "keyId", "21");
    }

    @Test
    void canManage_nullFirmId_isRejected_neverConsultsProprietorship() {
        // A BUSINESS key with a null firm_id can't be firm-scoped; canManage must
        // short-circuit to false without ever calling isProprietor (which would NPE
        // on a null firmId). Guards the `key.getFirmId() != null` half of the check.
        when(apiKeyService.getKey(21)).thenReturn(businessKey(null, false));

        handler.doRevoke(proprietor, 21);

        verify(message).send(proprietor, "treasuryapi.business.revoke.no-access");
        verify(firms, never()).isProprietor(anyInt(), org.mockito.ArgumentMatchers.any());
        verify(apiKeyService, never()).revokeKey(anyInt(), any());
    }

    @Test
    void doReissue_revokedKey_proprietorToldTerminal_neverReissues() {
        when(apiKeyService.getKey(21)).thenReturn(businessKey(7, true));
        when(firms.isProprietor(7, proprietorId)).thenReturn(true);

        handler.doReissue(proprietor, 21);

        verify(message).send(proprietor, "treasuryapi.business.reissue.revoked");
        verify(apiKeyService, never()).reissueKey(anyInt(), any());
    }
}
