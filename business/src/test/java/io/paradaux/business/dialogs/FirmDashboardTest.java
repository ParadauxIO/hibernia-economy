package io.paradaux.business.dialogs;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.paradaux.hibernia.framework.curator.*;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.hibernia.framework.usher.DialogManager;
import java.util.List;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

class FirmDashboardTest {
    private final Message message = mock(Message.class);
    private final DialogManager dialogs = mock(DialogManager.class);
    private final MenuFlow flow = mock(MenuFlow.class);
    private final Player player = mock(Player.class);
    private final FirmDashboard menu = new FirmDashboard(message, dialogs);
    private final FirmMenu.State state =
            new FirmMenu.State(42, "Acme", "$100", List.of("12 · Main ★"), List.of("Alex · Owner"));

    @BeforeEach
    void setup() {
        MockBukkit.mock();
        when(message.component(anyString())).thenAnswer(i -> Component.text(i.getArgument(0, String.class)));
        when(message.component(anyString(), any(), any())).thenReturn(Component.text("Acme"));
        when(flow.player()).thenReturn(player);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
    }

    @AfterEach
    void shutdown() {
        MockBukkit.unmock();
    }

    @Test
    void firmHoverShowsOwnerHeadquartersAndOpenHintWithoutItalicText() {
        when(message.component("business.menu.firm-id", "id", "42")).thenReturn(Component.text("Firm #42"));
        when(message.component("business.menu.owner", "owner", "Alex")).thenReturn(Component.text("Owner: Alex"));
        when(message.component("business.menu.headquarters", "hq", "town"))
                .thenReturn(Component.text("Headquarters: town"));
        var view = menu.main(new FirmMenu.State(List.of(new FirmMenu.Choice("42", "Acme", "Alex", "town"))), flow);
        var lore = view.items(0).get(0).icon().lore();
        assertThat(lore)
                .containsExactly(
                        Component.text("Firm #42")
                                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                        Component.text("Owner: Alex")
                                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                        Component.text("Headquarters: town")
                                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                        Component.empty().decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                        Component.text("business.menu.open-firm")
                                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
    }

    @Test
    void chooserAndListingsPaginateWithoutMakingEntriesTransferableActions() {
        var choices = IntStream.range(1, 47)
                .mapToObj(i -> new FirmMenu.Choice("" + i, "Firm" + i, "Alex", "town"))
                .toList();
        var chooser = menu.main(new FirmMenu.State(choices), flow);
        assertThat(chooser.pages()).isEqualTo(2);
        assertThat(chooser.items(0).get(0).payload()).isEqualTo("1");
        assertThat(chooser.items(1).get(0).payload()).isEqualTo("46");
        assertThat(chooser.items(0).get(50).action()).isEqualTo("next");
        var large = new FirmMenu.State(
                42,
                "Acme",
                "$100",
                List.of(),
                IntStream.range(0, 46).mapToObj(i -> "Staff" + i).toList());
        var staff = menu.staff(large, flow);
        assertThat(staff.pages()).isEqualTo(2);
        assertThat(staff.items(0).get(0).action()).isNull();
        assertThat(staff.items(0).get(49).action()).isEqualTo("manage");
        when(flow.page()).thenReturn(1);
        assertThat(menu.staff(large, flow).items(1).get(48).action()).isEqualTo("previous");
    }

    @Test
    void emptyChooserAndListsShowFeedbackWithoutConflictingWithContentSlots() {
        assertThat(menu.main(new FirmMenu.State(List.of()), flow)
                        .items(0)
                        .get(0)
                        .action())
                .isNull();
        var empty = new FirmMenu.State(42, "Acme", "$0", List.of(), List.of());
        assertThat(menu.staff(empty, flow).items(0).get(0).icon().getType()).isEqualTo(org.bukkit.Material.PAPER);
        assertThat(menu.accounts(empty, flow).items(0).get(49).action()).isEqualTo("manage");
    }

    @Test
    void dashboardOpensOneUsherFormWithAFreshSubmissionGuard() {
        assertThat(menu.main(state, flow).items(0).get(11).action()).isEqualTo("money");
        menu.money(state, flow);
        menu.money(state, flow);
        var captured = org.mockito.ArgumentCaptor.forClass(FirmMenu.State.class);
        verify(dialogs).open(eq(player), eq(FirmMenu.class), eq("money"), captured.capture());
        assertThat(captured.getValue().firmId).isEqualTo(42);
        assertThat(captured.getValue().submitted.get()).isFalse();
        verify(flow).close();
    }

    @Test
    void revokedMenuPermissionBlocksTransitionAndListPermissionHidesCachedEntries() {
        when(player.hasPermission("business.menu")).thenReturn(false);
        menu.money(state, flow);
        verifyNoInteractions(dialogs);
        when(player.hasPermission("business.staff.list")).thenReturn(false);
        assertThat(menu.staff(state, flow).items(0).get(0).icon().getType()).isEqualTo(org.bukkit.Material.PAPER);
    }

    @Test
    void selectionRejectsPayloadsOutsideTheOfferedFirms() {
        var selection = new FirmMenu.State(List.of(new FirmMenu.Choice("42", "Acme", "Alex", null)));
        var click = mock(MenuContext.class);
        when(click.payload(String.class)).thenReturn("99");
        menu.select(click, selection, flow);
        verify(player, never()).performCommand(anyString());
        when(click.payload(String.class)).thenReturn("42");
        menu.select(click, selection, flow);
        menu.select(click, selection, flow);
        verify(player, times(1)).performCommand("firm menu 42");
    }
}
