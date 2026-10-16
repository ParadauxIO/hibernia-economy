package io.paradaux.treasury.commands;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.GovernmentFine;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.FineWebhookService;
import io.paradaux.treasury.services.GovService;
import io.paradaux.treasury.services.MembershipService;
import io.paradaux.treasury.services.PlayerDirectoryService;
import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FineCommandAuthorizationTest {
    private static final int GOV_ID = 501;
    private static final int FIRM_ID = 502;
    private static final UUID SENDER_ID = UUID.randomUUID();
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("5.00");

    @Mock
    AccountService accounts;

    @Mock
    GovService government;

    @Mock
    MembershipService membership;

    @Mock
    PlayerDirectoryService directory;

    @Mock
    FineWebhookService webhook;

    @Mock
    Message message;

    @Mock
    Player sender;

    @Mock
    OfflinePlayer target;

    private FineCommand command;

    @BeforeEach
    void setUp() {
        command = new FineCommand(accounts, government, membership, directory, webhook, message);
    }

    @ParameterizedTest(name = "{0}: admin={1}, canSpend={2}, allowed={3}")
    @CsvSource({
        "issue, false, false, false", "issue, false, true, true", "issue, true, false, true",
        "firm, false, false, false", "firm, false, true, true", "firm, true, false, true",
        "revoke, false, false, false", "revoke, false, true, true", "revoke, true, false, true"
    })
    void finesRequireSpendAccessToTheGovernmentAccountOrAdmin(
            String route, boolean admin, boolean canSpend, boolean allowed) {
        Account account = account(GOV_ID, "Police");
        GovernmentFine fine = GovernmentFine.builder()
                .fineId(9)
                .govAccountId(GOV_ID)
                .debtorAccountId(FIRM_ID)
                .amount(AMOUNT)
                .reason("speeding")
                .build();
        if (route.equals("revoke")) {
            when(government.getFine(9)).thenReturn(fine);
            when(accounts.getAccountById(GOV_ID)).thenReturn(account);
        } else {
            when(accounts.getGovernmentAccountByName("Police")).thenReturn(account);
        }
        when(sender.hasPermission("treasury.gov.admin")).thenReturn(admin);
        when(sender.getUniqueId()).thenReturn(SENDER_ID);
        if (!admin) when(membership.canSpend(GOV_ID, SENDER_ID)).thenReturn(canSpend);
        if (allowed) {
            switch (route) {
                case "issue" -> {
                    when(target.hasPlayedBefore()).thenReturn(true);
                    when(target.getUniqueId()).thenReturn(TARGET_ID);
                    when(target.getName()).thenReturn("Offender");
                    when(government.issueFine(TARGET_ID, GOV_ID, AMOUNT, "speeding", SENDER_ID))
                            .thenReturn(fine);
                }
                case "firm" -> {
                    when(accounts.resolveBusinessAccountByToken("Acme")).thenReturn(account(FIRM_ID, "Acme"));
                    when(government.issueFine(FIRM_ID, GOV_ID, AMOUNT, "speeding", SENDER_ID))
                            .thenReturn(fine);
                }
                case "revoke" -> when(government.revokeFine(9, SENDER_ID)).thenReturn(fine);
            }
        }

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            invoke(route);
            if (!allowed) bukkit.verifyNoInteractions();
        }

        if (allowed) {
            switch (route) {
                case "issue" -> verify(government).issueFine(TARGET_ID, GOV_ID, AMOUNT, "speeding", SENDER_ID);
                case "firm" -> verify(government).issueFine(FIRM_ID, GOV_ID, AMOUNT, "speeding", SENDER_ID);
                case "revoke" -> verify(government).revokeFine(9, SENDER_ID);
            }
            if (route.equals("revoke")) verify(webhook).sendFineRevoked(fine);
            else verify(webhook).sendFineIssued(fine);
            verify(message, never()).send(sender, "treasury.gov.no-access");
            if (admin) verifyNoInteractions(membership);
        } else {
            verify(message).send(sender, "treasury.gov.no-access");
            assertNoFineMutation();
            verifyNoInteractions(target, directory, webhook);
        }
    }

    @ParameterizedTest
    @CsvSource({"issue", "firm", "revoke"})
    void globalTransferPermissionDoesNotGrantFineAccess(String route) {
        // Unlike government transfers, fines accept only admin or per-account spend access.
        when(sender.hasPermission(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0).equals("treasury.gov.account.transfer"));
        when(sender.getUniqueId()).thenReturn(SENDER_ID);
        Account account = account(GOV_ID, "Police");
        if (route.equals("revoke")) {
            when(government.getFine(9))
                    .thenReturn(GovernmentFine.builder().govAccountId(GOV_ID).build());
            when(accounts.getAccountById(GOV_ID)).thenReturn(account);
        } else {
            when(accounts.getGovernmentAccountByName("Police")).thenReturn(account);
        }

        invoke(route);

        verify(membership).canSpend(GOV_ID, SENDER_ID);
        verify(message).send(sender, "treasury.gov.no-access");
        assertNoFineMutation();
        verifyNoInteractions(target, directory, webhook);
    }

    @ParameterizedTest
    @CsvSource({"issue, false", "issue, true", "firm, false", "firm, true"})
    void missingOrArchivedAccountIsRejectedBeforeAuthorization(String route, boolean archived) {
        Account account = account(GOV_ID, "Police");
        account.setArchived(true);
        when(accounts.getGovernmentAccountByName("Police")).thenReturn(archived ? account : null);

        invoke(route);

        verify(message).send(sender, "treasury.gov.not-found", "account", "Police");
        verifyNoInteractions(sender, membership, government, target, directory, webhook);
    }

    private void invoke(String route) {
        switch (route) {
            case "issue" -> command.issue(sender, "Police", target, AMOUNT, "speeding");
            case "firm" -> command.fineFirm(sender, "Police", "Acme", AMOUNT, "speeding");
            case "revoke" -> command.revoke(sender, 9);
            default -> throw new IllegalArgumentException(route);
        }
    }

    private void assertNoFineMutation() {
        verify(government, never()).issueFine(any(UUID.class), anyInt(), any(), anyString(), any());
        verify(government, never()).issueFine(anyInt(), anyInt(), any(), anyString(), any());
        verify(government, never()).revokeFine(anyLong(), any());
    }

    private static Account account(int id, String name) {
        Account account = new Account();
        account.setAccountId(id);
        account.setDisplayName(name);
        return account;
    }
}
