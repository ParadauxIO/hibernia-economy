package io.paradaux.business.commands;

import static org.mockito.Mockito.*;

import io.paradaux.business.commands.resolvers.FirmName;
import io.paradaux.business.commands.resolvers.OnlineFirmName;
import io.paradaux.business.internal.model.Firm;
import io.paradaux.business.internal.model.config.FirmConfiguration;
import io.paradaux.business.model.RolePermission;
import io.paradaux.business.services.*;
import io.paradaux.business.threading.BusinessMessages;
import io.paradaux.treasury.api.SalesQueryApi;
import io.paradaux.treasury.api.TreasuryApi;
import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FinanceCommandGateTest {
    enum Operation {
        BALANCE,
        DEPOSIT,
        WITHDRAW,
        TRANSFER,
        PAY_PLAYER,
        PAY_BUSINESS,
        TRANSACTIONS,
        FIRM_WITHDRAW,
        FIRM_PAY_PLAYER,
        FIRM_PAY_BUSINESS,
        SEND,
        FIRM_TRANSACTIONS,
        SALES,
        SUMMARY,
        EXPORT
    }

    private final FirmService firms = mock(FirmService.class);
    private final FirmStaffService staff = mock(FirmStaffService.class);
    private final FirmTransactionService transactions = mock(FirmTransactionService.class);
    private final FirmNotificationService notifications = mock(FirmNotificationService.class);
    private final SalesQueryApi sales = mock(SalesQueryApi.class);
    private final FirmConfiguration config = mock(FirmConfiguration.class);
    private final TreasuryApi treasury = mock(TreasuryApi.class);
    private final BusinessMessages message = mock(BusinessMessages.class);
    private final Player player = mock(Player.class);
    private final FirmGate gate = new FirmGate(firms, staff, message);
    private final AccountCommands accounts =
            new AccountCommands(null, firms, gate, transactions, treasury, message, notifications, null);
    private final MiscCommands misc = new MiscCommands(firms, gate, transactions, treasury, message, notifications);
    private final SalesCommands salesCommands =
            new SalesCommands(sales, null, firms, staff, treasury, config, message, gate);

    private void run(Operation operation) {
        FirmName source = new FirmName("Acme");
        OnlineFirmName target = new OnlineFirmName("Globex");
        switch (operation) {
            case BALANCE -> accounts.accountBalance(player, source, 42);
            case DEPOSIT -> accounts.accountDeposit(player, source, 42, BigDecimal.TEN);
            case WITHDRAW -> accounts.accountWithdraw(player, source, 42, BigDecimal.TEN);
            case TRANSFER -> accounts.accountTransfer(player, source, 42, 51, BigDecimal.TEN);
            case PAY_PLAYER -> accounts.accountPayPlayer(player, source, 42, null, BigDecimal.TEN);
            case PAY_BUSINESS -> accounts.accountPayBusiness(player, source, 42, target, BigDecimal.TEN);
            case TRANSACTIONS -> accounts.accountTransactions(player, source, 42);
            case FIRM_WITHDRAW -> misc.withdraw(player, source, BigDecimal.TEN);
            case FIRM_PAY_PLAYER -> misc.payPlayer(player, source, null, BigDecimal.TEN);
            case FIRM_PAY_BUSINESS -> misc.payBusiness(player, source, target, BigDecimal.TEN);
            case SEND -> misc.send(player, source, target, BigDecimal.TEN);
            case FIRM_TRANSACTIONS -> misc.transactions(player, source);
            case SALES -> salesCommands.sales(player, source);
            case SUMMARY -> salesCommands.salesSummary(player, source);
            case EXPORT -> salesCommands.salesExport(player, source, 30);
        }
    }

    @Test
    void financialStaffCanWithdrawAndReadSalesButCannotToggleNotifications() {
        UUID actor = UUID.randomUUID();
        Firm source = new Firm();
        source.setFirmId(12);
        source.setDisplayName("Acme");
        when(player.getUniqueId()).thenReturn(actor);
        when(firms.getFirmByNameOrId("Acme")).thenReturn(source);
        when(staff.hasPermission(12, actor, RolePermission.FINANCIAL)).thenReturn(true);

        run(Operation.FIRM_WITHDRAW);
        verify(transactions).withdraw(12, actor, BigDecimal.TEN);
        run(Operation.SALES);
        verify(sales).listSales(argThat(query -> query.getFirmId().equals(12)));
        verify(message).send(player, "business.sales.empty", "firm", "Acme");

        salesCommands.salesToggle(player, new FirmName("Acme"));
        verify(message).send(player, "business.general.no-permission");
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void unauthorizedCallerCannotReadOrMoveFirmMoney(Operation operation) {
        Firm source = new Firm();
        source.setFirmId(12);
        Firm target = new Firm();
        target.setFirmId(99);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(firms.getFirmByNameOrId("Acme")).thenReturn(source);
        when(firms.getFirmByNameOrId("Globex")).thenReturn(target);

        run(operation);

        verify(message).send(player, "business.general.no-permission");
        verifyNoMoreInteractions(message);
        verifyNoInteractions(transactions, sales, config, treasury, notifications);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void missingSourceStopsWithoutSideEffects(Operation operation) {
        run(operation);
        switch (operation) {
            case FIRM_WITHDRAW, FIRM_PAY_PLAYER, FIRM_PAY_BUSINESS, SEND, FIRM_TRANSACTIONS ->
                verify(message).send(player, "business.firm.not-found", "firm", "Acme");
            default -> verify(message).send(player, "business.general.firm-not-found");
        }
        verifyNoMoreInteractions(message);
        verifyNoInteractions(transactions, sales, config, treasury, notifications, staff);
    }

    @ParameterizedTest
    @EnumSource(
            value = Operation.class,
            names = {"PAY_BUSINESS", "FIRM_PAY_BUSINESS", "SEND"})
    void sameFirmRejectionStillPrecedesAuthorization(Operation operation) {
        Firm source = new Firm();
        source.setFirmId(12);
        when(firms.getFirmByNameOrId("Acme")).thenReturn(source);
        when(firms.getFirmByNameOrId("Globex")).thenReturn(source);
        run(operation);
        verify(message).send(player, "business.finance.pay.same-firm");
        verifyNoMoreInteractions(message);
        verifyNoInteractions(transactions, staff, notifications);
    }
}
