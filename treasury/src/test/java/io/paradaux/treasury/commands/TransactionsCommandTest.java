package io.paradaux.treasury.commands;

import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.TransactionEntry;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.AuditService;
import io.paradaux.treasury.services.DataExportService;
import io.paradaux.treasury.services.LedgerService;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The audit routes are the in-game government's audit tool: gated solely by
 * {@code treasury.transactions.audit} and intentionally <em>bypassing</em>
 * {@code account_access} so an auditor can read any account regardless of
 * membership (ADT-18). These tests pin that intent — in particular that no
 * {@code canAccessAccount} membership check ever runs.
 */
@ExtendWith(MockitoExtension.class)
class TransactionsCommandTest {

    private static final int ACCOUNT_ID = 5;

    @Mock AccountService accountService;
    @Mock LedgerService ledgerService;
    @Mock DataExportService dataExportService;
    @Mock AuditService auditService;
    @Mock Message message;
    @Mock Player viewer;

    private TransactionsCommand cmd;

    @BeforeEach
    void setUp() {
        cmd = new TransactionsCommand(accountService, ledgerService, dataExportService, auditService, message);
    }

    private static Page<TransactionEntry> emptyPage() {
        return new Page<>(List.of(), 0, 0, 10);
    }

    // ---------- auditaccount ----------

    @Test
    void auditAccount_readsAnyAccountWithoutMembershipCheck_andRecordsAudit() {
        UUID viewerUuid = UUID.randomUUID();
        Account account = new Account();
        account.setAccountId(ACCOUNT_ID);
        account.setDisplayName("Acme Corp");
        when(accountService.hasAccountByAccountId(ACCOUNT_ID)).thenReturn(true);
        when(accountService.getAccountById(ACCOUNT_ID)).thenReturn(account);
        when(ledgerService.getTransactionHistory(ACCOUNT_ID, 0, 10)).thenReturn(emptyPage());
        when(viewer.getName()).thenReturn("Auditor");
        when(viewer.getUniqueId()).thenReturn(viewerUuid);

        cmd.auditAccount(viewer, ACCOUNT_ID);

        // The whole point: any account is readable, with NO account_access gate.
        verify(accountService, never()).canAccessAccount(any(), anyInt());
        verify(ledgerService).getTransactionHistory(ACCOUNT_ID, 0, 10);
        verify(message).send(viewer, "treasury.transactions.audit.empty", "target", "Acme Corp");
        // ...and the access is persisted to the shared audit log.
        verify(auditService).recordTransactionAudit(
                viewerUuid, "Auditor", ACCOUNT_ID, "/transactions auditaccount 5", 1);
    }

    @Test
    void auditAccount_unknownAccount_notFound_noLedgerReadOrAudit() {
        when(accountService.hasAccountByAccountId(ACCOUNT_ID)).thenReturn(false);

        cmd.auditAccount(viewer, ACCOUNT_ID);

        verify(message).send(viewer, "treasury.transactions.audit.not-found");
        verify(accountService, never()).canAccessAccount(any(), anyInt());
        verify(ledgerService, never()).getTransactionHistory(anyInt(), anyInt(), anyInt());
        verify(auditService, never()).recordTransactionAudit(any(), any(), anyInt(), any(), anyInt());
    }

    // ---------- audit <player> ----------

    @Test
    void auditPlayer_readsTargetLedgerWithoutMembershipCheck_andRecordsAudit() {
        UUID viewerUuid = UUID.randomUUID();
        UUID targetUuid = UUID.randomUUID();
        OfflinePlayer target = org.mockito.Mockito.mock(OfflinePlayer.class);
        when(target.hasPlayedBefore()).thenReturn(true);
        when(target.getUniqueId()).thenReturn(targetUuid);
        when(target.getName()).thenReturn("Bob");
        when(accountService.findPersonalAccountId(targetUuid)).thenReturn(7);
        when(ledgerService.getTransactionHistory(7, 0, 10)).thenReturn(emptyPage());
        when(viewer.getName()).thenReturn("Auditor");
        when(viewer.getUniqueId()).thenReturn(viewerUuid);

        cmd.auditPlayer(viewer, target);

        verify(accountService, never()).canAccessAccount(any(), anyInt());
        verify(ledgerService).getTransactionHistory(7, 0, 10);
        verify(message).send(viewer, "treasury.transactions.audit.empty", "target", "Bob");
        verify(auditService).recordTransactionAudit(
                viewerUuid, "Auditor", 7, "/transactions audit Bob", 1);
    }

    @Test
    void auditPlayer_unknownPlayer_isRejectedBeforeAnyLookupOrAudit() {
        OfflinePlayer target = org.mockito.Mockito.mock(OfflinePlayer.class);
        when(target.hasPlayedBefore()).thenReturn(false);
        when(target.isOnline()).thenReturn(false);

        cmd.auditPlayer(viewer, target);

        verify(message).send(viewer, "treasury.general.unknown-player");
        verify(accountService, never()).findPersonalAccountId(any());
        verify(ledgerService, never()).getTransactionHistory(anyInt(), anyInt(), anyInt());
        verify(auditService, never()).recordTransactionAudit(any(), any(), anyInt(), any(), anyInt());
    }

    @Test
    void auditPlayer_targetHasNoAccount_reportsNoAccount_noAudit() {
        UUID targetUuid = UUID.randomUUID();
        OfflinePlayer target = org.mockito.Mockito.mock(OfflinePlayer.class);
        when(target.hasPlayedBefore()).thenReturn(true);
        when(target.getUniqueId()).thenReturn(targetUuid);
        when(target.getName()).thenReturn("Bob");
        when(accountService.findPersonalAccountId(targetUuid)).thenReturn(null);

        cmd.auditPlayer(viewer, target);

        verify(message).send(viewer, "treasury.transactions.audit.no-account", "target", "Bob");
        verify(ledgerService, never()).getTransactionHistory(anyInt(), anyInt(), anyInt());
        verify(auditService, never()).recordTransactionAudit(any(), any(), anyInt(), any(), anyInt());
    }
}
