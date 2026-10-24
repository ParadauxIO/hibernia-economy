package io.paradaux.business.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.paradaux.business.dialogs.FirmDashboard;
import io.paradaux.business.dialogs.FirmMenu;
import io.paradaux.business.model.Firm;
import io.paradaux.business.services.*;
import io.paradaux.hibernia.framework.curator.MenuManager;
import io.paradaux.hibernia.framework.i18n.Message;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;

class MenuCommandsTest {
    @Test
    void chooserExcludesArchivedFirms() {
        var server = MockBukkit.mock();
        try {
            var plugin = MockBukkit.createMockPlugin();
            var player = server.addPlayer();
            player.setOp(true);
            var firms = mock(FirmService.class);
            var players = mock(FirmPlayerService.class);
            var menus = mock(MenuManager.class);
            var active = firm(1, false);
            var archived = firm(2, true);
            when(firms.listOwnedOrMemberFirms(player.getUniqueId())).thenReturn(List.of(archived, active));
            when(players.findByUuid(anyString())).thenReturn(Optional.empty());
            var command = new MenuCommands(
                    firms,
                    mock(FirmTransactionService.class),
                    mock(FirmAccountService.class),
                    mock(FirmStaffService.class),
                    players,
                    menus,
                    plugin,
                    mock(Message.class));
            command.menu(player);
            server.getScheduler().performOneTick();
            var state = ArgumentCaptor.forClass(FirmMenu.State.class);
            verify(menus).open(eq(player), eq(FirmDashboard.class), state.capture());
            var flow = mock(io.paradaux.hibernia.framework.curator.MenuFlow.class);
            var message = mock(Message.class);
            when(message.component(anyString())).thenReturn(net.kyori.adventure.text.Component.empty());
            when(message.component(anyString(), any(), any())).thenReturn(net.kyori.adventure.text.Component.empty());
            var view = new FirmDashboard(message, null).main(state.getValue(), flow);
            assertThat(view.items(0).values())
                    .filteredOn(i -> "select".equals(i.action()))
                    .extracting(i -> i.payload())
                    .containsExactly("1");
        } finally {
            MockBukkit.unmock();
        }
    }

    private Firm firm(int id, boolean archived) {
        var firm = new Firm();
        firm.setFirmId(id);
        firm.setDisplayName("Firm " + id);
        firm.setProprietorUuid("owner-uuid");
        firm.setArchived(archived);
        return firm;
    }
}
