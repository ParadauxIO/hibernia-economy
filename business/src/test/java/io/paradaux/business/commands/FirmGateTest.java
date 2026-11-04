package io.paradaux.business.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.paradaux.business.model.Firm;
import io.paradaux.business.model.RolePermission;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import io.paradaux.business.threading.BusinessMessages;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FirmGateTest {
    private final FirmService firms = mock(FirmService.class);
    private final FirmStaffService staff = mock(FirmStaffService.class);
    private final BusinessMessages message = mock(BusinessMessages.class);
    private final Player player = mock(Player.class);
    private final UUID actor = UUID.randomUUID();
    private final FirmGate gate = new FirmGate(firms, staff, message);

    private Firm activeFirm() {
        Firm firm = new Firm();
        firm.setFirmId(12);
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(player.getUniqueId()).thenReturn(actor);
        return firm;
    }

    @Test
    void missingFirmStopsBeforeAuthorizationAndSendsOneResponse() {
        assertThat(gate.resolveForFinances(player, "missing")).isEmpty();
        verify(message).send(player, "business.general.firm-not-found");
        verifyNoMoreInteractions(message);
        verifyNoInteractions(staff);
        verify(firms, never()).isProprietor(anyInt(), any());
    }

    @Test
    void namedMissingResponseRetainsTheReference() {
        assertThat(gate.resolveNamedForFinances(player, "404")).isEmpty();
        verify(message).send(player, "business.firm.not-found", "firm", "404");
        verifyNoMoreInteractions(message);
    }

    @Test
    void proprietorNeedsNoStaffRole() {
        Firm firm = activeFirm();
        when(firms.isProprietor(12, actor)).thenReturn(true);
        assertThat(gate.resolveForFinances(player, "Acme")).contains(firm);
        verifyNoInteractions(staff, message);
    }

    @ParameterizedTest
    @EnumSource(RolePermission.class)
    void onlyAdminAndFinancialRolesGrantFinanceAccess(RolePermission role) {
        Firm firm = activeFirm();
        lenient().when(staff.hasPermission(12, actor, role)).thenReturn(true);
        var result = gate.resolveNamedForFinances(player, "Acme");
        if (role == RolePermission.ADMIN || role == RolePermission.FINANCIAL) {
            assertThat(result).contains(firm);
            verifyNoInteractions(message);
        } else {
            assertThat(result).isEmpty();
            verify(message).send(player, "business.general.no-permission");
            verifyNoMoreInteractions(message);
        }
    }

    @Test
    void permissionForAnotherFirmDoesNotGrantAccess() {
        activeFirm();
        when(firms.isProprietor(99, actor)).thenReturn(true);
        when(staff.hasPermission(99, actor, RolePermission.ADMIN)).thenReturn(true);
        assertThat(gate.resolveForFinances(player, "Acme")).isEmpty();
        verify(message).send(player, "business.general.no-permission");
    }
}
