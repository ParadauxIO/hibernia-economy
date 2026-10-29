package io.paradaux.business.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.paradaux.business.commands.resolvers.FirmName;
import io.paradaux.business.model.Firm;
import io.paradaux.business.services.*;
import io.paradaux.hibernia.framework.commander.annotations.Permission;
import io.paradaux.hibernia.framework.commander.annotations.Route;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.api.TreasuryApi;
import java.math.BigDecimal;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PublicDepositCommandTest {
    @Test
    void bothSpellingsUseThePublicPermissionWithAndWithoutMemo() throws Exception {
        for (Class<?>[] params : new Class<?>[][] {
            {Player.class, FirmName.class, BigDecimal.class},
            {Player.class, FirmName.class, BigDecimal.class, String.class}
        }) {
            var handler = MiscCommands.class.getMethod("payInto", params);
            String suffix = params.length == 4 ? " <memo>" : "";
            assertThat(handler.getAnnotationsByType(Route.class))
                    .extracting(Route::value)
                    .containsExactlyInAnyOrder("deposit <firm> <amount>" + suffix, "pay into <firm> <amount>" + suffix);
            assertThat(handler.getAnnotation(Permission.class).value()).isEqualTo("business.pay");
        }
    }

    @Test
    void nonMemberCanPayWithMemoAndNotifiesTheFirm() {
        FirmService firms = mock(FirmService.class);
        FirmStaffService staff = mock(FirmStaffService.class);
        FirmTransactionService transfers = mock(FirmTransactionService.class);
        FirmNotificationService notifications = mock(FirmNotificationService.class);
        TreasuryApi treasury = mock(TreasuryApi.class);
        Message messages = mock(Message.class);
        Player player = mock(Player.class);
        UUID payer = UUID.randomUUID();
        Firm firm = new Firm();
        firm.setFirmId(12);
        firm.setDisplayName("Globex");
        when(firms.getFirmByNameOrId("Globex")).thenReturn(firm);
        when(player.getUniqueId()).thenReturn(payer);
        when(player.getName()).thenReturn("Customer");
        when(treasury.formatAmount(BigDecimal.TEN)).thenReturn("$10.00");

        new MiscCommands(firms, staff, transfers, treasury, messages, notifications)
                .payInto(player, new FirmName("Globex"), BigDecimal.TEN, "Invoice 42");

        verify(transfers).payIntoFirm(12, payer, BigDecimal.TEN, "Invoice 42");
        verifyNoInteractions(staff);
        verify(firms, never()).isProprietor(anyInt(), any());
        verify(messages).send(player, "business.finance.pay.into.success", "firm", "Globex", "amount", "$10.00");
        verify(notifications)
                .notifyFirmExcept(
                        12,
                        payer,
                        "business.notify.transfer.incoming",
                        "firm",
                        "Globex",
                        "amount",
                        "$10.00",
                        "sender",
                        "Customer");
    }
}
