package io.paradaux.treasury.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paradaux.treasury.internal.model.config.GovernmentConfiguration;
import io.paradaux.treasury.mappers.GovernmentFineMapper;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.GovernmentFine;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.PlayerDirectoryService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GovServiceImplTest {

    @Mock
    AccountService accountService;

    @Mock
    LedgerService ledgerService;

    @Mock
    GovernmentFineMapper fineMapper;

    @Mock
    GovernmentConfiguration govConfig;

    @Mock
    PlayerDirectoryService playerDirectory;

    private GovServiceImpl svc;

    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        svc = new GovServiceImpl(accountService, ledgerService, fineMapper, govConfig, playerDirectory);
    }

    @Test
    void createDepartmentAccount_rejectsWhenGovAccountAlreadyExists() {
        when(accountService.getGovernmentAccountByName("Treasury"))
                .thenReturn(Account.builder().build());

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
        Account created = Account.builder().displayName("Treasury").build();
        when(accountService.getGovernmentAccountByName("Treasury")).thenReturn(null);
        when(playerDirectory.resolveUuidByName("Treasury")).thenReturn(Optional.empty());
        when(accountService.createAccount(eq(AccountType.GOVERNMENT), any(), eq("Treasury")))
                .thenReturn(created);

        Account result = svc.createDepartmentAccount("Treasury", actor);

        assertThat(result).isSameAs(created);
        verify(accountService).createAccount(eq(AccountType.GOVERNMENT), any(), eq("Treasury"));
    }

    // ---- Fine double-submit: one fine row per ledger debit ----

    private static final int DEBTOR = 12;
    private static final int GOV = 50;

    private void stubFineAccounts() {
        Account gov = Account.builder()
                .accountId(GOV)
                .accountType(AccountType.GOVERNMENT)
                .build();
        when(accountService.getAccountById(GOV)).thenReturn(gov);
        when(accountService.getAccountById(DEBTOR)).thenReturn(Account.builder().build());
    }

    @Test
    void issueFine_recordsAFineAgainstItsDebit_andSendsNoDedupKey() {
        // No dedup key: an identical fine issued twice is two fines, not a collapsed
        // double-submit that reports success without debiting (PAR-570).
        stubFineAccounts();
        when(ledgerService.adminTransfer(any(TransferRequest.class))).thenReturn(900L);

        GovernmentFine fine = svc.issueFine(DEBTOR, GOV, new BigDecimal("25.00"), "littering", actor);

        ArgumentCaptor<TransferRequest> request = ArgumentCaptor.forClass(TransferRequest.class);
        verify(ledgerService).adminTransfer(request.capture());
        assertThat(request.getValue().dedupKey()).isNull();
        assertThat(fine.getTxnId()).isEqualTo(900L);
        verify(fineMapper).insertFine(fine);
    }
}
