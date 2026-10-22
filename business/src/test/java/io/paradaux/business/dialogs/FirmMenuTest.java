package io.paradaux.business.dialogs;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.hibernia.framework.usher.DialogFlow;
import java.util.List;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FirmMenuTest {
    private final FirmMenu menu = new FirmMenu();
    private final Player player = mock(Player.class);
    private final DialogFlow flow = mock(DialogFlow.class);
    private final FirmMenu.State state =
            new FirmMenu.State(42, "Acme", "$100.00", List.of("12 · Main ★"), List.of("Alex · Owner"));

    @BeforeEach
    void setup() {
        when(flow.player()).thenReturn(player);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(true);
    }

    @Test
    void moneyJourneyDispatchesOnceAndIgnoresOldCallbacks() {
        assertThat(menu.money(flow).inputs()).extracting(i -> i.key()).contains("operation", "amount", "target");
        menu.submit("PAY_PLAYER", "Alex", "10.25", state, flow);
        menu.submit("PAY_PLAYER", "Alex", "10.25", state, flow);
        menu.back(state, flow);
        verify(player, times(1)).performCommand("firm pay player 42 Alex 10.25");
        verify(player, times(1)).performCommand(anyString());
        verify(flow, times(1)).close();
    }

    @Test
    void revokingPermissionAfterOpeningFormPreventsSubmission() {
        menu.money(flow);
        when(player.hasPermission("business.finance")).thenReturn(false);
        assertThatThrownBy(() -> menu.submit("WITHDRAW", "", "10", state, flow))
                .isInstanceOf(NoPermissionException.class);
        verify(player, never()).performCommand(anyString());
        assertThat(menu.money(flow).inputs().getFirst().options())
                .extracting(o -> o.id())
                .containsExactly("PAY_INTO");
    }

    @Test
    void menuPermissionIsRecheckedAtSubmission() {
        when(player.hasPermission("business.menu")).thenReturn(false);
        menu.submit("HIRE", "Alex", null, state, flow);
        verify(player, never()).performCommand(anyString());
    }

    @Test
    void staffAndAccountFormsUseExistingAuthorizedCommands() {
        assertThat(menu.staff(flow).inputs().getFirst().options())
                .extracting(o -> o.id())
                .containsExactly("HIRE", "FIRE");
        assertThat(menu.accounts(flow).inputs().getFirst().options())
                .extracting(o -> o.id())
                .containsExactly("CREATE_ACCOUNT", "DEFAULT_ACCOUNT");
        assertThat(FirmMenu.Operation.HIRE.command(42, "Alex", null)).isEqualTo("firm hire 42 Alex");
        assertThat(FirmMenu.Operation.FIRE.command(42, "Alex", null)).isEqualTo("firm fire 42 Alex");
        assertThat(FirmMenu.Operation.DEFAULT_ACCOUNT.command(42, "12", null))
                .isEqualTo("firm account setdefault 42 12");
        assertThat(FirmMenu.Operation.CREATE_ACCOUNT.command(42, "Savings", null))
                .isEqualTo("firm account create 42 Savings");
        assertThat(FirmMenu.Operation.DEPOSIT.command(42, "ignored", "1.00")).isEqualTo("firm deposit 42 1.00");
        assertThat(FirmMenu.Operation.PAY_FIRM.command(42, "99", "2")).isEqualTo("firm pay business 42 99 2");
    }

    @Test
    void rejectsMissingFieldsAndRouteInjectionBeforeConsumingSession() {
        for (String target : List.of("", "Alex confirm", "Alex\nconfirm", "Alex\u0000")) {
            assertThatThrownBy(() -> menu.submit("FIRE", target, null, state, flow))
                    .isInstanceOf(BadCommandException.class);
        }
        assertThatThrownBy(() -> menu.submit("DEPOSIT", "", null, state, flow)).isInstanceOf(BadCommandException.class);
        assertThatThrownBy(() -> menu.submit("ADMIN", "Alex", "1", state, flow))
                .isInstanceOf(BadCommandException.class);
        menu.submit("FIRE", "Alex", null, state, flow);
        verify(player).performCommand("firm fire 42 Alex");
    }
}
