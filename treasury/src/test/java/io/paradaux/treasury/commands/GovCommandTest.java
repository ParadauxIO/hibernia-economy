package io.paradaux.treasury.commands;

import com.google.inject.Provider;
import io.paradaux.hibernia.framework.commander.CommandManager;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.GovService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.MembershipService;
import io.paradaux.treasury.services.PlayerDirectoryService;
import io.paradaux.treasury.utils.TreasuryConstants;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Exercises the private access gates through public command entrypoints, without a DB. */
@ExtendWith(MockitoExtension.class)
class GovCommandTest {
    private static final int SOURCE_ID = 501;
    private static final int TARGET_ID = 502;
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("5.00");

    @Mock AccountService accounts;
    @Mock LedgerService ledger;
    @Mock MembershipService membership;
    @Mock GovService government;
    @Mock PlayerDirectoryService directory;
    @Mock Message message;
    @Mock Provider<CommandManager> commandManager;
    @Mock Player player;
    @Mock ConsoleCommandSender console;
    private GovCommand command;

    @BeforeEach
    void setUp() {
        command = new GovCommand(accounts, ledger, membership, government, directory, message, commandManager);
    }

    @ParameterizedTest(name = "transfer: admin={0}, globalTransfer={1}, canSpend={2}, allowed={3}")
    @CsvSource({
            "false, false, false, false",
            "false, false, true, true",
            "false, true, false, true",
            "true, false, false, true"
    })
    void transfer_requiresSourceSpendAccessOrAnExplicitGlobalGrant(
            boolean admin, boolean globalTransfer, boolean canSpend, boolean allowed) {
        transferAccounts();
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.hasPermission("treasury.gov.admin")).thenReturn(admin);
        if (!admin) {
            when(player.hasPermission("treasury.gov.account.transfer")).thenReturn(globalTransfer);
            if (!globalTransfer) when(membership.canSpend(SOURCE_ID, PLAYER_ID)).thenReturn(canSpend);
        }

        command.accountTransfer(player, "Police", "Health", AMOUNT);

        if (allowed) {
            assertTransfer(player);
        } else {
            verify(message).send(player, "treasury.gov.no-access");
            verifyNoInteractions(ledger);
        }
    }

    @ParameterizedTest(name = "read: viewNode={0}, admin={1}, canView={2}, allowed={3}")
    @CsvSource({
            "false, false, false, false",
            "false, true, true, false",
            "true, false, false, false",
            "true, false, true, true",
            "true, true, false, true"
    })
    void balanceAndHistory_requireBothTheViewNodeAndAccountAccess(
            boolean viewNode, boolean admin, boolean canView, boolean allowed) {
        when(player.hasPermission("treasury.gov.account.view")).thenReturn(viewNode);
        if (viewNode) {
            sourceAccount();
            when(player.hasPermission("treasury.gov.admin")).thenReturn(admin);
            if (!admin) {
                when(player.getUniqueId()).thenReturn(PLAYER_ID);
                when(membership.canView(SOURCE_ID, PLAYER_ID)).thenReturn(canView);
            }
        }
        if (allowed) when(ledger.getTransactionHistory(SOURCE_ID, 0, 10)).thenReturn(new Page<>(List.of(), 0, 0, 10));

        command.accountBalance(player, "Police");
        command.accountHistory(player, "Police");

        if (allowed) {
            verify(accounts).getBalanceReadOnly(SOURCE_ID);
            verify(ledger).getTransactionHistory(SOURCE_ID, 0, 10);
            verify(message, never()).send(player, "treasury.gov.no-access");
        } else {
            verify(message, times(2)).send(player, "treasury.gov.no-access");
            verify(accounts, never()).getBalanceReadOnly(anyInt());
            verifyNoInteractions(ledger);
        }
    }

    @ParameterizedTest(name = "manage: manageNode={0}, admin={1}, authorizer={2}, allowed={3}")
    @CsvSource({
            "false, false, true, false",
            "false, true, false, false",
            "true, false, false, false",
            "true, false, true, true",
            "true, true, false, true"
    })
    void memberManagement_requiresManageNodeAndAuthorizerOrAdmin(
            boolean manageNode, boolean admin, boolean authorizer, boolean allowed) {
        when(player.hasPermission("treasury.gov.account.manage")).thenReturn(manageNode);
        if (manageNode) {
            sourceAccount();
            when(player.hasPermission("treasury.gov.admin")).thenReturn(admin);
            if (!admin) {
                when(player.getUniqueId()).thenReturn(PLAYER_ID);
                when(membership.isAuthorizer(SOURCE_ID, PLAYER_ID)).thenReturn(authorizer);
            }
        }

        command.memberRemoveGroup(player, "Police", "officers");

        if (allowed) {
            verify(membership).removeGroupMember(SOURCE_ID, "officers");
        } else {
            verify(message).send(player, "treasury.gov.no-access");
            verify(membership, never()).removeGroupMember(anyInt(), anyString());
        }
    }

    @Test
    void memberCanSpendButCannotManageOtherMembers() {
        transferAccounts();
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(membership.canSpend(SOURCE_ID, PLAYER_ID)).thenReturn(true);
        when(player.hasPermission("treasury.gov.account.manage")).thenReturn(true);
        when(player.hasPermission("treasury.gov.admin")).thenReturn(false);
        when(player.hasPermission("treasury.gov.account.transfer")).thenReturn(false);

        command.accountTransfer(player, "Police", "Health", AMOUNT);
        command.memberRemoveGroup(player, "Police", "officers");

        assertTransfer(player);
        verify(membership).isAuthorizer(SOURCE_ID, PLAYER_ID);
        verify(membership, never()).removeGroupMember(anyInt(), anyString());
        verify(message).send(player, "treasury.gov.no-access");
    }

    @Test
    void viewerCanReadButCannotSpendOrManage() {
        transferAccounts();
        when(player.getUniqueId()).thenReturn(PLAYER_ID);
        when(player.hasPermission("treasury.gov.account.view")).thenReturn(true);
        when(player.hasPermission("treasury.gov.account.manage")).thenReturn(true);
        when(player.hasPermission("treasury.gov.admin")).thenReturn(false);
        when(player.hasPermission("treasury.gov.account.transfer")).thenReturn(false);
        when(membership.canView(SOURCE_ID, PLAYER_ID)).thenReturn(true);
        when(ledger.getTransactionHistory(SOURCE_ID, 0, 10)).thenReturn(new Page<>(List.of(), 0, 0, 10));

        command.accountBalance(player, "Police");
        command.accountHistory(player, "Police");
        command.accountTransfer(player, "Police", "Health", AMOUNT);
        command.memberRemoveGroup(player, "Police", "officers");

        verify(accounts).getBalanceReadOnly(SOURCE_ID);
        verify(ledger).getTransactionHistory(SOURCE_ID, 0, 10);
        verify(ledger, never()).transfer(any());
        verify(ledger, never()).adminTransfer(any());
        verify(membership, never()).removeGroupMember(anyInt(), anyString());
        verify(message, times(2)).send(player, "treasury.gov.no-access");
    }

    @Test
    void consoleBypassesPermissionAndMembershipGates() {
        transferAccounts();
        when(ledger.getTransactionHistory(SOURCE_ID, 0, 10)).thenReturn(new Page<>(List.of(), 0, 0, 10));

        command.accountBalance(console, "Police");
        command.accountHistory(console, "Police");
        command.accountTransfer(console, "Police", "Health", AMOUNT);
        command.memberRemoveGroup(console, "Police", "officers");

        verify(accounts).getBalanceReadOnly(SOURCE_ID);
        verify(ledger).getTransactionHistory(SOURCE_ID, 0, 10);
        assertTransfer(console);
        verify(membership).removeGroupMember(SOURCE_ID, "officers");
        verifyNoMoreInteractions(membership);
        verifyNoInteractions(console);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrArchivedSourceIsRejectedEvenForConsole(boolean archived) {
        when(accounts.getGovernmentAccountByName("Police")).thenReturn(archived ? account(SOURCE_ID, "Police", true) : null);

        command.accountBalance(console, "Police");
        command.accountHistory(console, "Police");
        command.accountTransfer(console, "Police", "Health", AMOUNT);
        command.memberRemoveGroup(console, "Police", "officers");

        verify(message, times(4)).send(console, "treasury.gov.not-found", "account", "Police");
        verify(accounts, never()).getBalanceReadOnly(anyInt());
        verifyNoInteractions(ledger, membership, government);
    }

    @Test
    void archivedTransferDestinationIsRejectedEvenForConsole() {
        sourceAccount();
        when(accounts.getGovernmentAccountByName("Health")).thenReturn(account(TARGET_ID, "Health", true));

        command.accountTransfer(console, "Police", "Health", AMOUNT);

        verify(message).send(console, "treasury.gov.not-found", "account", "Health");
        verifyNoInteractions(ledger, membership);
    }

    private void sourceAccount() {
        when(accounts.getGovernmentAccountByName("Police")).thenReturn(account(SOURCE_ID, "Police", false));
    }

    private void transferAccounts() {
        sourceAccount();
        when(accounts.getGovernmentAccountByName("Health")).thenReturn(account(TARGET_ID, "Health", false));
    }

    private static Account account(int id, String name, boolean archived) {
        Account account = new Account();
        account.setAccountId(id);
        account.setDisplayName(name);
        account.setAccountType(AccountType.GOVERNMENT);
        account.setArchived(archived);
        return account;
    }

    private void assertTransfer(CommandSender sender) {
        ArgumentCaptor<TransferRequest> request = ArgumentCaptor.forClass(TransferRequest.class);
        if (sender instanceof Player) {
            verify(ledger).transfer(request.capture());
            verify(ledger, never()).adminTransfer(any());
            assertThat(request.getValue().initiator()).isEqualTo(PLAYER_ID);
        } else {
            verify(ledger).adminTransfer(request.capture());
            verify(ledger, never()).transfer(any());
            assertThat(request.getValue().initiator()).isEqualTo(TreasuryConstants.VIRTUAL_TREASURY_INITIATOR);
        }
        assertThat(request.getValue().fromAccountId()).isEqualTo(SOURCE_ID);
        assertThat(request.getValue().toAccountId()).isEqualTo(TARGET_ID);
        assertThat(request.getValue().amount()).isEqualByComparingTo(AMOUNT);
    }
}
