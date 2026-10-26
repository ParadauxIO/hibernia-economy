package io.paradaux.business.commands;

import io.paradaux.business.commands.resolvers.FirmName;
import io.paradaux.business.model.Firm;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmService;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.mockito.Mockito.*;

class ShopAliasCommandTest {
    @Test
    void managesAliasesForResolvedFirmWithPlayerIdentity() {
        var accounts = mock(FirmAccountService.class);
        var firms = mock(FirmService.class);
        var message = mock(Message.class);
        var player = mock(Player.class);
        UUID actor = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(actor);
        Firm firm = new Firm();
        firm.setFirmId(12);
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        var command = new AccountCommands(accounts, firms, null, null, null, message, null, null);
        command.setShopAlias(player, new FirmName("Acme"), 46, "acme");
        verify(accounts).setShopAlias(12, 46, "acme", actor);
        verify(message).send(player, "business.account.alias.set", "accountId", 46, "alias", "acme");
        command.clearShopAlias(player, new FirmName("Acme"), 46);
        verify(accounts).setShopAlias(12, 46, null, actor);
        verify(message).send(player, "business.account.alias.cleared", "accountId", 46);
        command.setShopAlias(player, new FirmName("Missing"), 46, "acme");
        verify(message).send(player, "business.general.firm-not-found");
        verifyNoMoreInteractions(accounts);
    }
}
