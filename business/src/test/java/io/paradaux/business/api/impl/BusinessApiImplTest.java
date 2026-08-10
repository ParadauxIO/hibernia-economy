package io.paradaux.business.api.impl;

import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmEmployee;
import io.paradaux.business.model.FirmPlayer;
import io.paradaux.business.model.FirmRole;
import io.paradaux.business.model.FirmRolePermission;
import io.paradaux.business.model.RolePermission;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmPlayerService;
import io.paradaux.business.services.FirmRequestService;
import io.paradaux.business.services.FirmRoleService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import io.paradaux.business.services.FirmTransactionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BusinessApiImplTest {

    @Mock FirmService firmService;
    @Mock FirmStaffService staffService;
    @Mock FirmRoleService roleService;
    @Mock FirmRequestService requestService;
    @Mock FirmPlayerService playerService;
    @Mock FirmAccountService accountService;
    @Mock FirmTransactionService transactionService;

    private BusinessApiImpl api;
    private final UUID actor = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        api = new BusinessApiImpl(firmService, staffService, roleService, requestService, playerService, accountService, transactionService);
    }

    // ---- FirmApi delegate ----

    @Test
    void firms_getFirm_delegates() {
        Firm f = new Firm();
        when(firmService.getAnyFirmByNameOrId("Acme")).thenReturn(f);
        assertThat(api.firms().getFirm("Acme")).isSameAs(f);
    }

    @Test
    void firms_getTotalBalance_delegates() {
        when(transactionService.getAggregateBalance(7)).thenReturn(new java.math.BigDecimal("1234.50"));
        assertThat(api.firms().getTotalBalance(7)).isEqualByComparingTo("1234.50");
    }

    @Test
    void firms_getFormattedTotalBalance_delegates() {
        when(transactionService.getFormattedAggregateBalance(7)).thenReturn("$1,234.50");
        assertThat(api.firms().getFormattedTotalBalance(7)).isEqualTo("$1,234.50");
    }

    @Test
    void firms_listFirms_delegates() {
        when(firmService.listAllFirms(2, 5)).thenReturn(List.of(new Firm()));
        assertThat(api.firms().listFirms(2, 5)).hasSize(1);
    }

    @Test
    void firms_getPlayerFirms_delegates() {
        when(firmService.listOwnedOrMemberFirms(actor)).thenReturn(List.of(new Firm()));
        assertThat(api.firms().getPlayerFirms(actor)).hasSize(1);
    }

    @Test
    void firms_createFirm_delegates() {
        Firm f = new Firm();
        when(firmService.createFirm("Acme", actor)).thenReturn(f);
        assertThat(api.firms().createFirm("Acme", actor)).isSameAs(f);
    }

    @Test
    void firms_disbandFirm_requiresProprietor() {
        when(firmService.isProprietor(7, actor)).thenReturn(false);
        assertThatThrownBy(() -> api.firms().disbandFirm(7, actor))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void firms_disbandFirm_succeedsForProprietor() {
        when(firmService.isProprietor(7, actor)).thenReturn(true);
        api.firms().disbandFirm(7, actor);
        verify(firmService).disbandFirm(7, actor);
    }

    @Test
    void firms_setHq_requiresAdmin() {
        when(staffService.hasPermission(7, actor, RolePermission.ADMIN)).thenReturn(false);
        assertThatThrownBy(() -> api.firms().setHq(7, "plot", actor))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void firms_setHq_succeedsForAdmin() {
        when(staffService.hasPermission(7, actor, RolePermission.ADMIN)).thenReturn(true);
        api.firms().setHq(7, "plot", actor);
        verify(firmService).updateFirmHq(7, "plot", actor);
    }

    @Test
    void firms_setDiscord_requiresAdmin() {
        when(staffService.hasPermission(7, actor, RolePermission.ADMIN)).thenReturn(false);
        assertThatThrownBy(() -> api.firms().setDiscord(7, "url", actor))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void firms_setDiscord_succeedsForAdmin() {
        when(staffService.hasPermission(7, actor, RolePermission.ADMIN)).thenReturn(true);
        api.firms().setDiscord(7, "url", actor);
        verify(firmService).updateFirmDiscord(7, "url", actor);
    }

    @Test
    void firms_isProprietor_delegates() {
        when(firmService.isProprietor(7, actor)).thenReturn(true);
        assertThat(api.firms().isProprietor(7, actor)).isTrue();
    }

    @Test
    void firms_getFirmByAccountId_unknown_returnsNull() {
        when(accountService.getFirmIdByAccountId(99)).thenReturn(null);
        assertThat(api.firms().getFirmByAccountId(99)).isNull();
    }

    @Test
    void firms_getFirmByAccountId_resolvesById() {
        Firm f = new Firm();
        when(accountService.getFirmIdByAccountId(99)).thenReturn(7);
        when(firmService.getAnyFirmById(7)).thenReturn(f);
        assertThat(api.firms().getFirmByAccountId(99)).isSameAs(f);
    }

    // ---- StaffApi delegate ----

    @Test
    void staff_getEmployees_delegates() {
        when(staffService.getCurrentEmployees(7)).thenReturn(List.of(new FirmEmployee()));
        assertThat(api.staff().getEmployees(7)).hasSize(1);
    }

    @Test
    void staff_getOnlineEmployees_delegates() {
        when(staffService.getOnlineEmployees(7)).thenReturn(List.of());
        assertThat(api.staff().getOnlineEmployees(7)).isEmpty();
    }

    @Test
    void staff_isEmployed_delegates() {
        when(staffService.isEmployedBy(7, target)).thenReturn(true);
        assertThat(api.staff().isEmployed(7, target)).isTrue();
    }

    @Test
    void staff_getRole_delegates() {
        when(staffService.getCurrentRole(7, target)).thenReturn("Manager");
        assertThat(api.staff().getRole(7, target)).isEqualTo("Manager");
    }

    @Test
    void staff_hasPermission_delegates() {
        when(staffService.hasPermission(7, target, RolePermission.ADMIN)).thenReturn(true);
        assertThat(api.staff().hasPermission(7, target, RolePermission.ADMIN)).isTrue();
    }

    @Test
    void staff_hire_delegates() {
        api.staff().hire(7, target, actor);
        verify(staffService).hireEmployee(7, target, actor);
    }

    @Test
    void staff_fire_delegates() {
        api.staff().fire(7, target, actor);
        verify(staffService).fireEmployee(7, target, actor);
    }

    @Test
    void staff_promote_delegates() {
        when(staffService.promoteEmployee(7, target, actor)).thenReturn("Manager");
        assertThat(api.staff().promote(7, target, actor)).isEqualTo("Manager");
    }

    @Test
    void staff_demote_delegates() {
        when(staffService.demoteEmployee(7, target, actor)).thenReturn("Employee");
        assertThat(api.staff().demote(7, target, actor)).isEqualTo("Employee");
    }

    @Test
    void staff_resign_delegates() {
        api.staff().resign(7, target);
        verify(staffService).resignFromFirm(7, target);
    }

    @Test
    void staff_hasPermissionForAccount_unknown_returnsFalse() {
        when(accountService.getFirmIdByAccountId(99)).thenReturn(null);
        assertThat(api.staff().hasPermissionForAccount(99, target, RolePermission.ADMIN)).isFalse();
    }

    @Test
    void staff_hasPermissionForAccount_resolvesAndChecks() {
        when(accountService.getFirmIdByAccountId(99)).thenReturn(7);
        when(staffService.hasPermission(7, target, RolePermission.ADMIN)).thenReturn(true);
        assertThat(api.staff().hasPermissionForAccount(99, target, RolePermission.ADMIN)).isTrue();
    }

    // ---- RoleApi delegate ----

    @Test
    void roles_getRoles_delegates() {
        when(roleService.getFirmRoles(7)).thenReturn(List.of(new FirmRole(7, "Lead", 1)));
        assertThat(api.roles().getRoles(7)).hasSize(1);
    }

    @Test
    void roles_getRolePermissions_delegates() {
        when(roleService.getFirmRolePermissions(7, "Lead")).thenReturn(List.of(
                new FirmRolePermission(7, "Lead", RolePermission.ADMIN)));
        assertThat(api.roles().getRolePermissions(7, "Lead")).hasSize(1);
    }

    @Test
    void roles_createRole_delegates() {
        api.roles().createRole(7, "Lead", 3, actor);
        verify(roleService).createRole(7, "Lead", 3, actor);
    }

    @Test
    void roles_deleteRole_delegates() {
        api.roles().deleteRole(7, "Lead", actor);
        verify(roleService).deleteRole(7, "Lead", actor);
    }

    @Test
    void roles_addPermission_delegates() {
        api.roles().addPermission(7, "Lead", "ADMIN", actor);
        verify(roleService).addRolePermission(7, "Lead", "ADMIN", actor);
    }

    @Test
    void roles_removePermission_delegates() {
        api.roles().removePermission(7, "Lead", "ADMIN", actor);
        verify(roleService).removeRolePermission(7, "Lead", "ADMIN", actor);
    }

    // ---- RequestApi delegate ----

    @Test
    void requests_offerEmployment_delegates() {
        api.requests().offerEmployment(7, target, actor);
        verify(requestService).offerEmployment(7, target, actor);
    }

    @Test
    void requests_rescindOffer_delegates() {
        api.requests().rescindOffer(7, target, actor);
        verify(requestService).rescindEmploymentOffer(7, target, actor);
    }

    @Test
    void requests_acceptOffer_delegates() {
        api.requests().acceptOffer(7, target, actor);
        verify(requestService).acceptEmploymentOffer(7, target, actor);
    }

    @Test
    void requests_rejectOffer_delegates() {
        api.requests().rejectOffer(7, target, actor);
        verify(requestService).rejectEmploymentOffer(7, target, actor);
    }

    // ---- PlayerApi delegate ----

    @Test
    void players_findByUuid_delegates() {
        when(playerService.findByUuid(target)).thenReturn(Optional.of(new FirmPlayer()));
        assertThat(api.players().findByUuid(target)).isPresent();
    }

    @Test
    void players_findByName_delegates() {
        when(playerService.findByName("alice")).thenReturn(Optional.of(new FirmPlayer()));
        assertThat(api.players().findByName("alice")).isPresent();
    }

    @Test
    void players_searchByPrefix_delegates() {
        when(playerService.searchByPrefix("a", 5)).thenReturn(List.of(new FirmPlayer()));
        assertThat(api.players().searchByPrefix("a", 5)).hasSize(1);
    }
}
