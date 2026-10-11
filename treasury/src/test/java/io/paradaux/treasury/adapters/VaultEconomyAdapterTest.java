package io.paradaux.treasury.adapters;

import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.testsupport.TestConfigs;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Vault carries no idempotency token, so the adapter's reported outcome is the only
 * thing standing between a caller and a retry. A movement that committed must be
 * reported SUCCESS no matter what happens while the response is being built.
 */
@ExtendWith(MockitoExtension.class)
class VaultEconomyAdapterTest {

    @Mock AccountService accountService;
    @Mock LedgerService ledgerService;
    @Mock TaxApi taxApi;
    @Mock OfflinePlayer player;

    private final UUID uuid = UUID.randomUUID();
    private VaultEconomyAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new VaultEconomyAdapter(accountService, ledgerService, TestConfigs.economy(), taxApi);
        when(player.getUniqueId()).thenReturn(uuid);
    }

    @Test
    void deposit_reportsSuccessEvenWhenTheBalanceReadAfterTheCommitFails() {
        // The deposit commits, then the informational balance read blows up. If that
        // escaped, the caller would see a failed deposit and retry → double credit.
        when(accountService.findRedirectedAccount(uuid)).thenThrow(new IllegalStateException("db down"));

        EconomyResponse response = adapter.depositPlayer(player, 25.0D);

        assertThat(response.type).isEqualTo(EconomyResponse.ResponseType.SUCCESS);
        assertThat(response.amount).isEqualTo(25.0D);
        verify(ledgerService).vaultDeposit(any(), eq(uuid), eq(new BigDecimal("25.00")), any(), any(), any(), any());
    }

    @Test
    void deposit_reportsTheNewBalanceOnSuccess() {
        when(accountService.findRedirectedAccount(uuid)).thenReturn(Optional.empty());
        when(accountService.findPersonalAccountId(uuid)).thenReturn(7);
        when(accountService.getBalanceReadOnly(7)).thenReturn(new BigDecimal("125.00"));

        EconomyResponse response = adapter.depositPlayer(player, 25.0D);

        assertThat(response.type).isEqualTo(EconomyResponse.ResponseType.SUCCESS);
        assertThat(response.balance).isEqualTo(125.0D);
    }

    @Test
    void deposit_thatDidNotCommit_reportsFailureRatherThanThrowing() {
        when(ledgerService.vaultDeposit(any(), eq(uuid), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("ledger down"));
        when(accountService.findRedirectedAccount(uuid)).thenThrow(new IllegalStateException("db down"));

        EconomyResponse response = adapter.depositPlayer(player, 25.0D);

        assertThat(response.type).isEqualTo(EconomyResponse.ResponseType.FAILURE);
        assertThat(response.errorMessage).isEqualTo("ledger down");
    }

    @Test
    void withdraw_reportsSuccessEvenWhenTheBalanceReadAfterTheCommitFails() {
        when(accountService.findRedirectedAccount(uuid)).thenThrow(new IllegalStateException("db down"));

        EconomyResponse response = adapter.withdrawPlayer(player, 25.0D);

        assertThat(response.type).isEqualTo(EconomyResponse.ResponseType.SUCCESS);
        verify(ledgerService).vaultWithdraw(any(), eq(uuid), eq(new BigDecimal("25.00")), any(), any(), any(), any());
    }
}
