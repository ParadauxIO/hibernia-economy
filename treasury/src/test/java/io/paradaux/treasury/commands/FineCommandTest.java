package io.paradaux.treasury.commands;

import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.GovernmentFine;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.FineWebhookService;
import io.paradaux.treasury.services.GovService;
import io.paradaux.treasury.services.MembershipService;
import io.paradaux.treasury.services.PlayerDirectoryService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FineCommandTest {

    private static final int GOV_ID = 50;
    private static final int FIRM_ACCOUNT_ID = 12;
    private static final BigDecimal AMOUNT = new BigDecimal("5.00");

    @Mock AccountService accountService;
    @Mock GovService govService;
    @Mock MembershipService membershipService;
    @Mock PlayerDirectoryService playerDirectory;
    @Mock FineWebhookService fineWebhook;
    @Mock Message message;
    @Mock Player sender;

    private final UUID senderUuid = UUID.randomUUID();
    private FineCommand command;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        command = new FineCommand(accountService, govService, membershipService, playerDirectory, fineWebhook, message);
        bukkit = mockStatic(Bukkit.class);

        Account gov = new Account();
        gov.setAccountId(GOV_ID);
        gov.setDisplayName("Police");
        lenient().when(accountService.getGovernmentAccountByName("Police")).thenReturn(gov);
        lenient().when(accountService.getAccountById(GOV_ID)).thenReturn(gov);
        lenient().when(sender.hasPermission("treasury.gov.admin")).thenReturn(true);
        lenient().when(sender.getUniqueId()).thenReturn(senderUuid);
        lenient().when(accountService.formatAmount(any())).thenReturn("$5.00");
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private static Account firmAccount() {
        Account firm = new Account();
        firm.setAccountId(FIRM_ACCOUNT_ID);
        firm.setDisplayName("Acme Corporate Account");
        return firm;
    }

    // ---- /fine firm ----

    @Test
    void fineFirm_resolvesTheBareFirmNameToItsCorporateAccount() {
        // Firm accounts are named "<firm> Corporate Account"; the single-word <firm>
        // argument can only ever be the bare name, which an exact match never finds.
        when(accountService.resolveBusinessAccountByToken(anyString())).thenCallRealMethod();
        when(accountService.getBusinessAccountByName("Acme")).thenReturn(null);
        when(accountService.getBusinessAccountByName("Acme Corporate Account")).thenReturn(firmAccount());
        GovernmentFine fine = GovernmentFine.builder().fineId(3).amount(AMOUNT).build();
        when(govService.issueFine(FIRM_ACCOUNT_ID, GOV_ID, AMOUNT, "dumping", senderUuid)).thenReturn(fine);

        command.fineFirm(sender, "Police", "Acme", AMOUNT, "dumping");

        verify(message).send(sender, "treasury.fine.firm.issued",
                "firm", "Acme Corporate Account", "amount", "$5.00", "reason", "dumping");
        verify(fineWebhook).sendFineIssued(fine);
    }

    @Test
    void fineFirm_unknownFirm_reportsNotFoundAndFinesNobody() {
        when(accountService.resolveBusinessAccountByToken("Ghost")).thenReturn(null);

        command.fineFirm(sender, "Police", "Ghost", AMOUNT, "dumping");

        verify(message).send(sender, "treasury.fine.firm.not-found", "firm", "Ghost");
        verify(govService, never()).issueFine(anyInt(), anyInt(), any(), any(), any());
    }

    // ---- /fine issue ----

    @Test
    void issue_notifiesTheFinedPlayerBeforePostingTheWebhook(@Mock OfflinePlayer target, @Mock Player online) {
        UUID targetUuid = UUID.randomUUID();
        when(target.hasPlayedBefore()).thenReturn(true);
        when(target.getUniqueId()).thenReturn(targetUuid);
        when(target.getName()).thenReturn("Offender");
        GovernmentFine fine = GovernmentFine.builder().fineId(4).playerUuid(targetUuid).amount(AMOUNT).build();
        when(govService.issueFine(targetUuid, GOV_ID, AMOUNT, "speeding", senderUuid)).thenReturn(fine);
        bukkit.when(() -> Bukkit.getPlayer(targetUuid)).thenReturn(online);

        command.issue(sender, "Police", target, AMOUNT, "speeding");

        // The webhook is a blocking network call: the in-game notice must not wait on it.
        InOrder order = inOrder(message, fineWebhook);
        order.verify(message).send(online, "treasury.fine.issued.notify", "amount", "$5.00", "reason", "speeding");
        order.verify(fineWebhook).sendFineIssued(fine);
    }

    // ---- /fine revoke ----

    @Test
    void revoke_firmFine_completesWithoutLookingUpAPlayer() {
        // A firm fine has no playerUuid; Bukkit.getPlayer(null) throws, and by then
        // the refund has already committed.
        GovernmentFine fine = GovernmentFine.builder()
                .fineId(9).playerUuid(null).debtorAccountId(FIRM_ACCOUNT_ID).govAccountId(GOV_ID)
                .amount(AMOUNT).reason("dumping").build();
        when(govService.getFine(9)).thenReturn(fine);
        when(govService.revokeFine(9, senderUuid)).thenReturn(fine);
        when(accountService.getAccountById(FIRM_ACCOUNT_ID)).thenReturn(firmAccount());

        command.revoke(sender, 9);

        verify(message).send(sender, "treasury.fine.revoked",
                "id", "9", "player", "Acme Corporate Account", "amount", "$5.00");
        verify(fineWebhook).sendFineRevoked(fine);
        bukkit.verify(() -> Bukkit.getPlayer((UUID) null), never());
    }

    @Test
    void revoke_playerFine_notifiesTheOnlinePlayer(@Mock Player online, @Mock OfflinePlayer offline) {
        UUID playerUuid = UUID.randomUUID();
        bukkit.when(() -> Bukkit.getOfflinePlayer(playerUuid)).thenReturn(offline);
        GovernmentFine fine = GovernmentFine.builder()
                .fineId(8).playerUuid(playerUuid).govAccountId(GOV_ID)
                .amount(AMOUNT).reason("speeding").build();
        when(govService.getFine(8)).thenReturn(fine);
        when(govService.revokeFine(8, senderUuid)).thenReturn(fine);
        bukkit.when(() -> Bukkit.getPlayer(playerUuid)).thenReturn(online);

        command.revoke(sender, 8);

        verify(message).send(online, "treasury.fine.revoked.notify", "amount", "$5.00", "reason", "speeding");
        verify(fineWebhook).sendFineRevoked(fine);
    }
}
