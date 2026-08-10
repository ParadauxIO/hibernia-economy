package io.paradaux.treasury.services.impl;

import io.paradaux.treasury.mappers.GovernmentFineMapper;
import io.paradaux.treasury.model.config.GovernmentConfiguration;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.PlayerDirectoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
