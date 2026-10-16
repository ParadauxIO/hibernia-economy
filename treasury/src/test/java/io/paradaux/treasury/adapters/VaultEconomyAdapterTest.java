package io.paradaux.treasury.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.model.tax.TaxResult;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.testsupport.TestConfigs;
import io.paradaux.treasury.utils.TreasuryConstants;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Vault carries no idempotency token, so the adapter's reported outcome is the only
 * thing standing between a caller and a retry. A movement that committed must be
 * reported SUCCESS no matter what happens while the response is being built.
 */
@ExtendWith(MockitoExtension.class)
class VaultEconomyAdapterTest {

    @Mock
    AccountService accountService;

    @Mock
    LedgerService ledgerService;

    @Mock
    TaxApi taxApi;

    @Mock
    OfflinePlayer player;

    private final UUID uuid = UUID.randomUUID();
    private VaultEconomyAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new VaultEconomyAdapter(accountService, ledgerService, TestConfigs.economy(), taxApi);
        lenient().when(player.getUniqueId()).thenReturn(uuid);
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

    // ---- amount guard: the one-cent floor, non-finite doubles, rounding ----

    @ParameterizedTest
    @ValueSource(
            doubles = {0.0D, -1.0D, 0.0099D, 0.005D, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void depositAndWithdraw_rejectUnpayableAmounts_withAFailureResponse_neverAnException(double amount) {
        EconomyResponse deposit = adapter.depositPlayer(player, amount);
        EconomyResponse withdraw = adapter.withdrawPlayer(player, amount);

        for (EconomyResponse r : new EconomyResponse[] {deposit, withdraw}) {
            assertThat(r.type).isEqualTo(EconomyResponse.ResponseType.FAILURE);
            assertThat(r.errorMessage).isEqualTo("Amount must be at least 0.01");
        }
        verifyNoInteractions(ledgerService, taxApi);
    }

    @Test
    void exactlyOneCent_isPayable() {
        assertThat(adapter.depositPlayer(player, 0.01D).type).isEqualTo(EconomyResponse.ResponseType.SUCCESS);
        assertThat(adapter.withdrawPlayer(player, 0.01D).type).isEqualTo(EconomyResponse.ResponseType.SUCCESS);
        verify(ledgerService).vaultDeposit(any(), eq(uuid), eq(new BigDecimal("0.01")), any(), any(), any(), any());
        verify(ledgerService).vaultWithdraw(any(), eq(uuid), eq(new BigDecimal("0.01")), any(), any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({
        "0.015, 0.02", // half-even: up to the even cent
        "0.025, 0.02", // half-even: down to the even cent
        "10.005, 10.00",
        "19.999, 20.00",
        "1234.5, 1234.50",
    })
    void deposit_postsTheAmountRoundedHalfEvenToCents(double amount, String posted) {
        adapter.depositPlayer(player, amount);
        verify(ledgerService).vaultDeposit(any(), eq(uuid), eq(new BigDecimal(posted)), any(), any(), any(), any());
        // Source income tax is assessed on exactly what was credited.
        verify(taxApi).applySourceIncomeTax(eq(uuid), eq(new BigDecimal(posted)), any());
    }

    @Test
    void deposit_memoAndInitiator_identifyTheVaultCaller() {
        adapter.depositPlayer(player, 5.0D);
        ArgumentCaptor<String> plugin = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> memo = ArgumentCaptor.forClass(String.class);
        verify(ledgerService)
                .vaultDeposit(
                        plugin.capture(),
                        eq(uuid),
                        eq(new BigDecimal("5.00")),
                        memo.capture(),
                        eq(TreasuryConstants.VIRTUAL_TREASURY_INITIATOR),
                        isNull(),
                        isNull());
        assertThat(memo.getValue()).isEqualTo("Vault deposit (" + plugin.getValue() + ")");
    }

    @Test
    void deposit_staysSuccessful_whenSourceIncomeTaxFailsOrThrows() {
        when(taxApi.applySourceIncomeTax(eq(uuid), any(), any()))
                .thenReturn(new TaxResult.Failed("tax account archived"))
                .thenThrow(new IllegalStateException("tax down"));

        assertThat(adapter.depositPlayer(player, 5.0D).type).isEqualTo(EconomyResponse.ResponseType.SUCCESS);
        assertThat(adapter.depositPlayer(player, 5.0D).type).isEqualTo(EconomyResponse.ResponseType.SUCCESS);
        verify(ledgerService, times(2)).vaultDeposit(any(), eq(uuid), any(), any(), any(), any(), any());
    }

    @Test
    void withdraw_thatTheLedgerRefuses_reportsFailureWithTheReasonAndCurrentBalance() {
        when(ledgerService.vaultWithdraw(any(), eq(uuid), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Insufficient funds"));
        when(accountService.findRedirectedAccount(uuid)).thenReturn(Optional.empty());
        when(accountService.findPersonalAccountId(uuid)).thenReturn(7);
        when(accountService.getBalanceReadOnly(7)).thenReturn(new BigDecimal("3.50"));

        EconomyResponse response = adapter.withdrawPlayer(player, 10.0D);

        assertThat(response.type).isEqualTo(EconomyResponse.ResponseType.FAILURE);
        assertThat(response.errorMessage).isEqualTo("Insufficient funds");
        assertThat(response.balance).isEqualTo(3.5D);
        assertThat(response.amount).isEqualTo(10.0D);
    }

    @Test
    void withdraw_success_reportsAmountAndNewBalance_andIsNotIncomeTaxed() {
        when(accountService.findRedirectedAccount(uuid)).thenReturn(Optional.empty());
        when(accountService.findPersonalAccountId(uuid)).thenReturn(7);
        when(accountService.getBalanceReadOnly(7)).thenReturn(new BigDecimal("90.00"));

        EconomyResponse response = adapter.withdrawPlayer(player, 10.0D);

        assertThat(response.type).isEqualTo(EconomyResponse.ResponseType.SUCCESS);
        assertThat(response.amount).isEqualTo(10.0D);
        assertThat(response.balance).isEqualTo(90.0D);
        verify(ledgerService).vaultWithdraw(any(), eq(uuid), eq(new BigDecimal("10.00")), any(), any(), any(), any());
        verifyNoInteractions(taxApi);
    }

    // ---- balance / has: redirects and the cent boundary ----

    @Test
    void balance_ofARedirectedUuid_isTheTargetAccountsBalance() {
        when(accountService.findRedirectedAccount(uuid)).thenReturn(Optional.of(42));
        when(accountService.getBalanceReadOnly(42)).thenReturn(new BigDecimal("1000.00"));

        assertThat(adapter.getBalance(player)).isEqualTo(1000.0D);
        verify(accountService, never()).findPersonalAccountId(any());
    }

    @Test
    void balance_withoutAnyAccount_isZero() {
        when(accountService.findRedirectedAccount(uuid)).thenReturn(Optional.empty());
        when(accountService.findPersonalAccountId(uuid)).thenReturn(null);

        assertThat(adapter.getBalance(player)).isZero();
        verify(accountService, never()).getBalanceReadOnly(anyInt());
    }

    @ParameterizedTest
    @CsvSource({
        "10.00, true",
        "9.99, true",
        "10.01, false",
        "10.004, true", // rounds to 10.00 — what a withdrawal would actually take
        "10.006, false", // rounds to 10.01
        "-5, true",
    })
    void has_comparesAgainstTheBalanceAtCentPrecision(double amount, boolean expected) {
        when(accountService.findRedirectedAccount(uuid)).thenReturn(Optional.empty());
        when(accountService.findPersonalAccountId(uuid)).thenReturn(7);
        when(accountService.getBalanceReadOnly(7)).thenReturn(new BigDecimal("10.00"));

        assertThat(adapter.has(player, amount)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY})
    void has_nonFiniteAmount_isFalse_notAnException(double amount) {
        assertThat(adapter.has(player, amount)).isFalse();
    }

    // ---- account existence / creation ----

    @Test
    void hasAccount_redirectedUuid_countsEvenWithoutAPersonalRow() {
        when(accountService.findRedirectedAccount(uuid)).thenReturn(Optional.of(42));
        assertThat(adapter.hasAccount(player)).isTrue();
        verify(accountService, never()).hasPersonalAccount(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void hasAccount_otherwiseReflectsThePersonalAccount(boolean personal) {
        when(accountService.findRedirectedAccount(uuid)).thenReturn(Optional.empty());
        when(accountService.hasPersonalAccount(uuid)).thenReturn(personal);
        assertThat(adapter.hasAccount(player)).isEqualTo(personal);
    }

    @Test
    void createPlayerAccount_redirectedUuid_createsNothing() {
        when(accountService.findRedirectedAccount(uuid)).thenReturn(Optional.of(42));
        assertThat(adapter.createPlayerAccount(player)).isFalse();
        verify(ledgerService, never()).resolveOrCreatePersonal(any());
    }

    @ParameterizedTest
    @CsvSource({"false, true", "true, false"}) // existed before -> reports created?
    void createPlayerAccount_reportsWhetherItCreatedOne(boolean existed, boolean created) {
        when(accountService.findRedirectedAccount(uuid)).thenReturn(Optional.empty());
        when(accountService.hasPersonalAccount(uuid)).thenReturn(existed);
        assertThat(adapter.createPlayerAccount(player)).isEqualTo(created);
        verify(ledgerService).resolveOrCreatePersonal(uuid); // idempotent either way
    }

    // ---- identity / formatting / banks ----

    @ParameterizedTest
    @CsvSource({
        "0, 0.00",
        "1234.5, '1,234.50'",
        "0.015, 0.02",
        "0.025, 0.02",
        "1000000, '1,000,000.00'",
        "-12.5, -12.50",
    })
    void format_twoDecimalsHalfEvenWithGrouping(double amount, String expected) {
        assertThat(adapter.format(amount)).isEqualTo(expected);
    }

    @Test
    void identityAndBankSupport() {
        assertThat(adapter.getName()).isEqualTo("TreasuryEconomy");
        assertThat(adapter.fractionalDigits()).isEqualTo(2);
        assertThat(adapter.hasBankSupport()).isFalse();
        assertThat(adapter.getBanks()).isEmpty();
        assertThat(adapter.bankDeposit("bank", 5).type).isEqualTo(EconomyResponse.ResponseType.NOT_IMPLEMENTED);
        assertThat(adapter.isEnabled()).isTrue();
        adapter.setEnabled(false);
        assertThat(adapter.isEnabled()).isFalse();
    }
}
