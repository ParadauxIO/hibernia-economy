package io.paradaux.treasuryrestapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.*;

import io.paradaux.treasuryrestapi.dto.PlayerTransferRequest;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.mapper.AccountMapper;
import io.paradaux.treasuryrestapi.mapper.FirmMapper;
import io.paradaux.treasuryrestapi.mapper.LedgerMapper;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PAR-144 remains defensive for pre-V38 data, which the migrated DB cannot insert. */
class LegacyNameCollisionGuardTest {
    private final AccountMapper accounts = mock(AccountMapper.class);
    private final FirmMapper players = mock(FirmMapper.class);
    private final LedgerMapper ledger = mock(LedgerMapper.class);
    private final UUID player = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void legacyBareNameAccountResolutionStillRefusesAmbiguity() {
        when(players.findPlayerUuidByName("Treasury")).thenReturn(player);
        when(accounts.existsGovernmentAccountByName("Treasury")).thenReturn(true);
        var error = catchThrowableOfType(
                ApiException.class, () -> new AccountService(accounts, players).resolvePlayerAccount(null, "Treasury"));
        assertThat(error.getErrorCode()).isEqualTo("AMBIGUOUS_NAME");
        assertThat(error.getStatus().value()).isEqualTo(409);
        verify(accounts, never()).findPersonalAccountIdByOwner(any());
    }

    @Test
    void legacyAmbiguousTransferNeverReachesTheLedger() {
        when(players.findPlayerUuidByName("Treasury")).thenReturn(player);
        when(accounts.existsGovernmentAccountByName("Treasury")).thenReturn(true);
        var error = catchThrowableOfType(
                ApiException.class,
                () -> new TransferService(accounts, ledger, players)
                        .transferToPlayer(
                                new VerifiedToken(1L, player, "PERSONAL", 1L, null),
                                new PlayerTransferRequest(null, null, "Treasury", "25.00", null),
                                null));
        assertThat(error.getErrorCode()).isEqualTo("AMBIGUOUS_NAME");
        verifyNoInteractions(ledger);
        verify(accounts, never()).findPersonalAccountIdByOwner(any());
    }

    @Test
    void explicitUuidResolutionStillReturnsTheSpecifiedPersonalWallet() {
        when(accounts.findPersonalAccountIdByOwner(player)).thenReturn(7L);
        when(players.findPlayerNameByUuid(player)).thenReturn("Treasury");
        var response = new AccountService(accounts, players).resolvePlayerAccount(player.toString(), null);
        assertThat(response.accountId()).isEqualTo(7L);
        assertThat(response.playerUuid()).isEqualTo(player.toString());
        verify(accounts, never()).existsGovernmentAccountByName(any());
    }
}
