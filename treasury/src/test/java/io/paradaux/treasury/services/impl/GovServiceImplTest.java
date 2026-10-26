package io.paradaux.treasury.services.impl;

import io.paradaux.treasury.mappers.GovernmentFineMapper;
import io.paradaux.treasury.model.config.GovernmentConfiguration;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.GovernmentFine;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.PlayerDirectoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GovServiceImplTest {

    @Mock AccountService accountService;
    @Mock LedgerService ledgerService;
    @Mock GovernmentFineMapper fineMapper;
    @Mock GovernmentConfiguration govConfig;
    @Mock PlayerDirectoryService playerDirectory;

    private GovServiceImpl svc;

    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        svc = new GovServiceImpl(accountService, ledgerService, fineMapper, govConfig, playerDirectory);
    }

    @Test
    void createDepartmentAccount_rejectsWhenGovAccountAlreadyExists() {
        when(accountService.getGovernmentAccountByName("Treasury")).thenReturn(new Account());

        assertThatThrownBy(() -> svc.createDepartmentAccount("Treasury", actor))
                .isInstanceOf(IllegalArgumentException.class);
        verify(accountService, never()).createAccount(any(), any(), any());
    }

    // PAR-144: refuse to mint a GOVERNMENT account whose name already belongs to a
    // known player — that player↔GOVERNMENT name collision is what makes bare-name
    // resolution ambiguous everywhere downstream.
    @Test
    void createDepartmentAccount_rejectsWhenNameIsAnExistingPlayer() {
        when(accountService.getGovernmentAccountByName("DCGovernmentOR")).thenReturn(null);
        when(playerDirectory.resolveUuidByName("DCGovernmentOR")).thenReturn(Optional.of(UUID.randomUUID()));

        assertThatThrownBy(() -> svc.createDepartmentAccount("DCGovernmentOR", actor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("player");
        verify(accountService, never()).createAccount(any(), any(), any());
    }

    @Test
    void createDepartmentAccount_createsWhenNameIsFree() {
        Account created = new Account();
        created.setDisplayName("Treasury");
        when(accountService.getGovernmentAccountByName("Treasury")).thenReturn(null);
        when(playerDirectory.resolveUuidByName("Treasury")).thenReturn(Optional.empty());
        when(accountService.createAccount(eq(AccountType.GOVERNMENT), any(), eq("Treasury"))).thenReturn(created);

        Account result = svc.createDepartmentAccount("Treasury", actor);

        assertThat(result).isSameAs(created);
        verify(accountService).createAccount(eq(AccountType.GOVERNMENT), any(), eq("Treasury"));
    }

    // ---- Fine double-submit: one fine row per ledger debit ----

    private static final int DEBTOR = 12;
    private static final int GOV = 50;

    private void stubFineAccounts() {
        Account gov = new Account();
        gov.setAccountId(GOV);
        gov.setAccountType(AccountType.GOVERNMENT);
        when(accountService.getAccountById(GOV)).thenReturn(gov);
        when(accountService.getAccountById(DEBTOR)).thenReturn(new Account());
    }

    @Test
    void issueFine_freshDebit_recordsAFineWithoutProbingForAnExistingOne() {
        stubFineAccounts();
        when(ledgerService.adminTransferChecked(any(TransferRequest.class)))
                .thenReturn(new LedgerService.TransferResult(900L, true));

        GovernmentFine fine = svc.issueFine(DEBTOR, GOV, new BigDecimal("25.00"), "littering", actor);

        assertThat(fine.getTxnId()).isEqualTo(900L);
        verify(fineMapper).insertFine(fine);
        // The locking lookup is reserved for the deduplicated path (see mapper javadoc).
        verify(fineMapper, never()).findFineByTxnIdForUpdate(900L);
    }

    @Test
    void issueFine_deduplicatedDebit_returnsTheExistingFineInsteadOfInsertingASecond() {
        // The ledger collapsed a double-submit onto txn 900 (created=false). A second
        // fine row for that one debit would be separately revocable — a double refund.
        stubFineAccounts();
        when(ledgerService.adminTransferChecked(any(TransferRequest.class)))
                .thenReturn(new LedgerService.TransferResult(900L, false));
        GovernmentFine existing = GovernmentFine.builder().fineId(7).txnId(900L).build();
        when(fineMapper.findFineByTxnIdForUpdate(900L)).thenReturn(existing);

        GovernmentFine fine = svc.issueFine(DEBTOR, GOV, new BigDecimal("25.00"), "littering", actor);

        assertThat(fine).isSameAs(existing);
        verify(fineMapper, never()).insertFine(any());
    }

    @Test
    void issueFine_deduplicatedDebitWithNoFineOnRecord_stillRecordsOne() {
        // Defensive: a debit with no fine row must not stay unrecorded.
        stubFineAccounts();
        when(ledgerService.adminTransferChecked(any(TransferRequest.class)))
                .thenReturn(new LedgerService.TransferResult(900L, false));
        when(fineMapper.findFineByTxnIdForUpdate(900L)).thenReturn(null);

        GovernmentFine fine = svc.issueFine(DEBTOR, GOV, new BigDecimal("25.00"), "littering", actor);

        assertThat(fine.getTxnId()).isEqualTo(900L);
        verify(fineMapper).insertFine(fine);
    }
}
