package io.paradaux.business.commands;

import static org.mockito.Mockito.*;

import io.paradaux.business.commands.resolvers.FirmName;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.RolePermission;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import io.paradaux.business.services.FirmTransactionService;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.api.TreasuryApi;
import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AccountTransferCommandTest {
    private final FirmService firms = mock(FirmService.class);
    private final FirmStaffService staff = mock(FirmStaffService.class);
    private final FirmTransactionService transfers = mock(FirmTransactionService.class);
    private final TreasuryApi treasury = mock(TreasuryApi.class);
    private final Message message = mock(Message.class);
    private final Player player = mock(Player.class);
    private final UUID actor = UUID.randomUUID();
    private final FirmName name = new FirmName("Acme");
    private final AccountCommands commands =
            new AccountCommands(null, firms, staff, transfers, treasury, message, null, null);

    @BeforeEach
    void setup() {
        Firm firm = new Firm();
        firm.setFirmId(12);
        firm.setDisplayName("Acme");
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(player.getUniqueId()).thenReturn(actor);
    }

    @Test
    void nonFinanceStaffCannotTransfer() {
        commands.accountTransfer(player, name, 42, 51, BigDecimal.TEN);
        verifyNoInteractions(transfers);
        verify(message).send(player, "business.general.no-permission");
    }

    @Test
    void unknownFirmDoesNotTransfer() {
        commands.accountTransfer(player, new FirmName("Missing"), 42, 51, BigDecimal.TEN);
        verifyNoInteractions(transfers);
        verify(message).send(player, "business.general.firm-not-found");
    }

    @Test
    void proprietorTransfersWithoutMemoAndGetsReceipt() {
        when(firms.isProprietor(12, actor)).thenReturn(true);
        when(treasury.formatAmount(BigDecimal.TEN)).thenReturn("$10.00");
        commands.accountTransfer(player, name, 42, 51, BigDecimal.TEN);
        verify(transfers).transferBetweenAccounts(12, 42, 51, actor, BigDecimal.TEN, null);
        verify(message)
                .send(
                        player,
                        "business.account.transfer.success",
                        "firm",
                        "Acme",
                        "fromAccountId",
                        42,
                        "toAccountId",
                        51,
                        "amount",
                        "$10.00");
    }

    @Test
    void financialStaffCanTransferWithMemo() {
        when(staff.hasPermission(12, actor, RolePermission.FINANCIAL)).thenReturn(true);
        commands.accountTransfer(player, name, 42, 51, BigDecimal.TEN, "July reserve funding");
        verify(transfers).transferBetweenAccounts(12, 42, 51, actor, BigDecimal.TEN, "July reserve funding");
    }
}
