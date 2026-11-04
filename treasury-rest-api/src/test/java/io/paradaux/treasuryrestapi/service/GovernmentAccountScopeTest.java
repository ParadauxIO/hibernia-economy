package io.paradaux.treasuryrestapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.mapper.*;
import io.paradaux.treasuryrestapi.model.Account;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GovernmentAccountScopeTest {
    @Test
    void membershipCannotExpandGovernmentHistoryOrFeedScope() {
        var accounts = mock(AccountMapper.class);
        var ledger = mock(LedgerMapper.class);
        var memberships = mock(MembershipMapper.class);
        var service = new TransactionService(accounts, ledger, memberships, mock(FirmMapper.class), 3);
        UUID owner = UUID.randomUUID();
        var token = new VerifiedToken(7, owner, "GOVERNMENT", 42L, null);
        when(accounts.findById(42)).thenReturn(new Account());
        when(accounts.findById(43)).thenReturn(new Account());
        when(memberships.isMember(43, owner)).thenReturn(1);
        when(ledger.findTransactionsByAccount(42, 10, 0)).thenReturn(List.of());
        when(ledger.findTransactionsSince(42, 0, 3, 10)).thenReturn(List.of());
        assertThat(service.getTransactions(token, 42, 1, 10).accountId()).isEqualTo(42);
        assertThat(service.getTransactionFeed(token, 42, 0, 10).accountId()).isEqualTo(42);
        clearInvocations(ledger);
        assertThatThrownBy(() -> service.getTransactions(token, 43, 1, 10))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.getTransactionFeed(token, 43, 0, 10))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));
        verifyNoInteractions(ledger);
        // Personal membership access remains supported.
        service.getTransactions(new VerifiedToken(8, owner, "PERSONAL", 42L, null), 43, 1, 10);
        verify(ledger).findTransactionsByAccount(43, 10, 0);
    }
}
