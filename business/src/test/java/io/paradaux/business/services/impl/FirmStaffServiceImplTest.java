package io.paradaux.business.services.impl;

import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.InternalException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.business.mappers.FirmRoleMapper;
import io.paradaux.business.mappers.FirmStaffMapper;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmEmployee;
import io.paradaux.business.model.FirmRolePermission;
import io.paradaux.business.model.RolePermission;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirmStaffServiceImplTest {

    @Mock FirmService firms;
    @Mock FirmRoleMapper roles;
    @Mock FirmStaffMapper staff;
    @Mock FirmAccountService accounts;

    private FirmStaffServiceImpl svc;

    private final UUID actor = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        svc = new FirmStaffServiceImpl(firms, roles, staff, accounts);
    }

    private Firm acme() {
        Firm f = new Firm();
        f.setFirmId(1);
        f.setDisplayName("Acme");
        f.setProprietorUuid(UUID.randomUUID().toString());
        f.setCreatedAt(LocalDateTime.now());
        return f;
    }

    // ---------- hireEmployee (public, ADMIN-gated) ----------

    @Test
    void hireEmployee_failsForUnknownFirm() {
        when(firms.getFirmById(99)).thenReturn(null);
        assertThatThrownBy(() -> svc.hireEmployee(99, target, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void hireEmployee_rejectsCallerWithoutAdmin() {
        // actor is not the proprietor and not employed → no ADMIN permission
        Firm firm = acme();
        when(firms.getFirmById(1)).thenReturn(firm);
        // hasPermission returns false (proprietor mismatch + not employed)
        assertThatThrownBy(() -> svc.hireEmployee(1, target, actor))
                .isInstanceOf(io.paradaux.hibernia.framework.exceptions.NoPermissionException.class);
        verify(staff, never()).insertEmployment(anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void hireEmployee_succeedsWhenCallerIsProprietor() {
        // Proprietor is the actor → hasPermission returns true → body runs
        Firm firm = acme();
        when(firms.getFirmById(1)).thenReturn(firm);
        // hasPermission checks firms.isProprietor first
        when(firms.isProprietor(1, actor)).thenReturn(true);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(false);
        when(roles.findLowestRole(1)).thenReturn("Employee");
        when(staff.insertEmployment(1, target.toString(), "Employee", actor.toString())).thenReturn(1);

        svc.hireEmployee(1, target, actor);

        verify(staff).insertEmployment(1, target.toString(), "Employee", actor.toString());
        verify(accounts).syncAllFirmAccounts(1);
    }

    // ---------- hireEmployeeFromInvite (invite-only path, no ADMIN gate) ----------
    // The body checks (self, already-employed, lowest-role fallback, insert-zero)
    // are now tested through hireEmployeeFromInvite so they don't need to stub
    // the ADMIN check for every case.

    @Test
    void hireEmployeeFromInvite_failsForUnknownFirm() {
        when(firms.getFirmById(99)).thenReturn(null);
        assertThatThrownBy(() -> svc.hireEmployeeFromInvite(99, target, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void hireEmployeeFromInvite_rejectsHiringSelf() {
        Firm firm = acme();
        when(firms.getFirmById(1)).thenReturn(firm);
        assertThatThrownBy(() -> svc.hireEmployeeFromInvite(1, actor, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void hireEmployeeFromInvite_rejectsAlreadyEmployed() {
        Firm firm = acme();
        when(firms.getFirmById(1)).thenReturn(firm);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(true);
        assertThatThrownBy(() -> svc.hireEmployeeFromInvite(1, target, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void hireEmployeeFromInvite_fallsBackToEmployeeWhenNoLowestRole() {
        Firm firm = acme();
        when(firms.getFirmById(1)).thenReturn(firm);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(false);
        when(roles.findLowestRole(1)).thenReturn(null);
        when(staff.insertEmployment(1, target.toString(), "Employee", actor.toString())).thenReturn(1);

        svc.hireEmployeeFromInvite(1, target, actor);

        verify(staff).insertEmployment(1, target.toString(), "Employee", actor.toString());
        verify(accounts).syncAllFirmAccounts(1);
    }

    @Test
    void hireEmployeeFromInvite_usesConfiguredLowestRole() {
        Firm firm = acme();
        when(firms.getFirmById(1)).thenReturn(firm);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(false);
        when(roles.findLowestRole(1)).thenReturn("Intern");
        when(staff.insertEmployment(1, target.toString(), "Intern", actor.toString())).thenReturn(1);

        svc.hireEmployeeFromInvite(1, target, actor);

        verify(staff).insertEmployment(1, target.toString(), "Intern", actor.toString());
    }

    @Test
    void hireEmployeeFromInvite_throwsInternalWhenInsertReturnsZero() {
        Firm firm = acme();
        when(firms.getFirmById(1)).thenReturn(firm);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(false);
        when(roles.findLowestRole(1)).thenReturn("Employee");
        when(staff.insertEmployment(1, target.toString(), "Employee", actor.toString())).thenReturn(0);

        assertThatThrownBy(() -> svc.hireEmployeeFromInvite(1, target, actor))
                .isInstanceOf(InternalException.class);
        verify(accounts, never()).syncAllFirmAccounts(anyInt());
    }

    // ---------- fireEmployee ----------

    @Test
    void fireEmployee_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.fireEmployee("Ghost", target, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void fireEmployee_requiresAdminPermission() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        // actor is not proprietor and not employed -> no perms
        when(firms.isProprietor(1, actor)).thenReturn(false);
        when(staff.isEmployedBy(1, actor.toString())).thenReturn(false);

        assertThatThrownBy(() -> svc.fireEmployee("Acme", target, actor))
                .isInstanceOf(NoPermissionException.class);
    }

    @Test
    void fireEmployee_cannotFireProprietor() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(firms.isProprietor(1, actor)).thenReturn(true); // actor is proprietor
        when(firms.isProprietor(1, target)).thenReturn(true); // target also proprietor

        assertThatThrownBy(() -> svc.fireEmployee("Acme", target, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void fireEmployee_cannotFireSelf() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(firms.isProprietor(1, actor)).thenReturn(true);

        assertThatThrownBy(() -> svc.fireEmployee("Acme", actor, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void fireEmployee_endsEmploymentAndSyncs() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(firms.isProprietor(1, actor)).thenReturn(true);
        when(firms.isProprietor(1, target)).thenReturn(false);
        when(staff.endCurrentEmployment(1, target.toString(), actor.toString())).thenReturn(1);

        svc.fireEmployee("Acme", target, actor);

        verify(staff).endCurrentEmployment(1, target.toString(), actor.toString());
        verify(accounts).syncAllFirmAccounts(1);
    }

    @Test
    void fireEmployee_throwsInternalWhenEndReturnsNotOne() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(firms.isProprietor(1, actor)).thenReturn(true);
        when(firms.isProprietor(1, target)).thenReturn(false);
        when(staff.endCurrentEmployment(1, target.toString(), actor.toString())).thenReturn(0);

        assertThatThrownBy(() -> svc.fireEmployee("Acme", target, actor))
                .isInstanceOf(InternalException.class);
    }

    // ---------- promoteEmployee ----------

    @Test
    void promoteEmployee_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.promoteEmployee("Ghost", target, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void promoteEmployee_cannotPromoteSelf() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        assertThatThrownBy(() -> svc.promoteEmployee("Acme", actor, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void promoteEmployee_requiresAdminPermission() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(firms.isProprietor(1, actor)).thenReturn(false);
        when(staff.isEmployedBy(1, actor.toString())).thenReturn(false);
        assertThatThrownBy(() -> svc.promoteEmployee("Acme", target, actor))
                .isInstanceOf(NoPermissionException.class);
    }

    @Test
    void promoteEmployee_failsIfTargetNotEmployed() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(firms.isProprietor(1, actor)).thenReturn(true);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(false);
        assertThatThrownBy(() -> svc.promoteEmployee("Acme", target, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void promoteEmployee_failsAtTopRank() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(firms.isProprietor(1, actor)).thenReturn(true);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(true);
        when(staff.getCurrentRole(1, target.toString())).thenReturn("Manager");
        when(roles.getRank(1, "Manager")).thenReturn(2);
        when(roles.findRoleAbove(1, 2)).thenReturn(null);
        assertThatThrownBy(() -> svc.promoteEmployee("Acme", target, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void promoteEmployee_movesToNextRole() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(firms.isProprietor(1, actor)).thenReturn(true);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(true);
        when(staff.getCurrentRole(1, target.toString())).thenReturn("Employee");
        when(roles.getRank(1, "Employee")).thenReturn(5);
        when(roles.findRoleAbove(1, 5)).thenReturn("Supervisor");
        when(staff.updateCurrentRole(1, target.toString(), "Supervisor")).thenReturn(1);

        String result = svc.promoteEmployee("Acme", target, actor);

        assertThat(result).isEqualTo("Supervisor");
        verify(accounts).syncAllFirmAccounts(1);
    }

    @Test
    void promoteEmployee_blankCurrentRole_throwsInternal() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(firms.isProprietor(1, actor)).thenReturn(true);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(true);
        when(staff.getCurrentRole(1, target.toString())).thenReturn("   ");
        assertThatThrownBy(() -> svc.promoteEmployee("Acme", target, actor))
                .isInstanceOf(InternalException.class);
    }

    @Test
    void promoteEmployee_missingRankInDb_throwsInternal() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(firms.isProprietor(1, actor)).thenReturn(true);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(true);
        when(staff.getCurrentRole(1, target.toString())).thenReturn("Manager");
        when(roles.getRank(1, "Manager")).thenReturn(null);
        assertThatThrownBy(() -> svc.promoteEmployee("Acme", target, actor))
                .isInstanceOf(InternalException.class);
    }

    // ---------- demoteEmployee ----------

    @Test
    void demoteEmployee_movesDown() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(firms.isProprietor(1, actor)).thenReturn(true);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(true);
        when(staff.getCurrentRole(1, target.toString())).thenReturn("Manager");
        when(roles.getRank(1, "Manager")).thenReturn(3);
        when(roles.findRoleBelow(1, 3)).thenReturn("Supervisor");
        when(staff.updateCurrentRole(1, target.toString(), "Supervisor")).thenReturn(1);

        assertThat(svc.demoteEmployee("Acme", target, actor)).isEqualTo("Supervisor");
        verify(accounts).syncAllFirmAccounts(1);
    }

    @Test
    void demoteEmployee_failsAtBottomRank() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(firms.isProprietor(1, actor)).thenReturn(true);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(true);
        when(staff.getCurrentRole(1, target.toString())).thenReturn("Employee");
        when(roles.getRank(1, "Employee")).thenReturn(5);
        when(roles.findRoleBelow(1, 5)).thenReturn(null);
        assertThatThrownBy(() -> svc.demoteEmployee("Acme", target, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void demoteEmployee_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.demoteEmployee("Ghost", target, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void demoteEmployee_cannotDemoteSelf() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        assertThatThrownBy(() -> svc.demoteEmployee("Acme", actor, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void demoteEmployee_targetNotEmployed_throws() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(firms.isProprietor(1, actor)).thenReturn(true);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(false);
        assertThatThrownBy(() -> svc.demoteEmployee("Acme", target, actor))
                .isInstanceOf(BadCommandException.class);
    }

    // ---------- resign ----------

    @Test
    void resign_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.resignFromFirm("Ghost", target))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void resign_notEmployed_throws() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(false);
        assertThatThrownBy(() -> svc.resignFromFirm("Acme", target))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void resign_endsEmploymentAndSyncs() {
        Firm firm = acme();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(true);

        svc.resignFromFirm("Acme", target);

        verify(staff).endCurrentEmployment(1, target.toString(), target.toString());
        verify(accounts).syncAllFirmAccounts(1);
    }

    // ---------- hasPermission ----------

    @Test
    void hasPermission_proprietorAlwaysTrue() {
        when(firms.isProprietor(1, actor)).thenReturn(true);
        assertThat(svc.hasPermission(1, actor, RolePermission.ADMIN)).isTrue();
    }

    @Test
    void hasPermission_notEmployedFalse() {
        when(firms.isProprietor(1, actor)).thenReturn(false);
        when(staff.isEmployedBy(1, actor.toString())).thenReturn(false);
        assertThat(svc.hasPermission(1, actor, RolePermission.ADMIN)).isFalse();
    }

    @Test
    void hasPermission_blankRoleFalse() {
        when(firms.isProprietor(1, actor)).thenReturn(false);
        when(staff.isEmployedBy(1, actor.toString())).thenReturn(true);
        when(staff.getCurrentRole(1, actor.toString())).thenReturn("  ");
        assertThat(svc.hasPermission(1, actor, RolePermission.ADMIN)).isFalse();
    }

    @Test
    void hasPermission_returnsTrueIfRoleHasPerm() {
        when(firms.isProprietor(1, actor)).thenReturn(false);
        when(staff.isEmployedBy(1, actor.toString())).thenReturn(true);
        when(staff.getCurrentRole(1, actor.toString())).thenReturn("Manager");
        when(roles.listPermissionsByRole(1, "Manager")).thenReturn(List.of(
                new FirmRolePermission(1, "Manager", RolePermission.FINANCIAL)
        ));
        assertThat(svc.hasPermission(1, actor, RolePermission.FINANCIAL)).isTrue();
    }

    @Test
    void hasPermission_returnsFalseIfRoleLacksPerm() {
        when(firms.isProprietor(1, actor)).thenReturn(false);
        when(staff.isEmployedBy(1, actor.toString())).thenReturn(true);
        when(staff.getCurrentRole(1, actor.toString())).thenReturn("Manager");
        when(roles.listPermissionsByRole(1, "Manager")).thenReturn(List.of(
                new FirmRolePermission(1, "Manager", RolePermission.FINANCIAL)
        ));
        assertThat(svc.hasPermission(1, actor, RolePermission.ADMIN)).isFalse();
    }

    // ---------- getCurrentEmployees / getProprietorAsEmployee ----------

    @Test
    void getCurrentEmployees_prependsProprietor() {
        Firm firm = acme();
        when(firms.getAnyFirmByNameOrId("Acme")).thenReturn(firm);
        when(roles.findProprietorRoleName(1)).thenReturn("Boss");
        when(staff.listCurrentEmployeesByFirm(1)).thenReturn(new java.util.ArrayList<>(List.of(
                new FirmEmployee(1, target.toString(), "Employee", LocalDateTime.now(), null, actor.toString(), null, true)
        )));

        List<FirmEmployee> emps = svc.getCurrentEmployees("Acme");

        assertThat(emps).hasSize(2);
        assertThat(emps.getFirst().getRoleName()).isEqualTo("Boss");
        assertThat(emps.getFirst().getPlayerUuid()).isEqualTo(firm.getProprietorUuid());
    }

    @Test
    void getCurrentEmployees_unknownFirm_throws() {
        when(firms.getAnyFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.getCurrentEmployees("Ghost"))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void getProprietorAsEmployee_fallsBackToProprietorWhenNullRole() {
        Firm firm = acme();
        when(firms.getAnyFirmByNameOrId("Acme")).thenReturn(firm);
        when(roles.findProprietorRoleName(1)).thenReturn(null);

        FirmEmployee emp = svc.getProprietorAsEmployee("Acme");
        assertThat(emp.getRoleName()).isEqualTo("Proprietor");
    }

    @Test
    void getProprietorAsEmployee_unknownFirm_throws() {
        when(firms.getAnyFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.getProprietorAsEmployee("Ghost"))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void isEmployedBy_delegates() {
        when(staff.isEmployedBy(1, target.toString())).thenReturn(true);
        assertThat(svc.isEmployedBy(1, target)).isTrue();
    }

    @Test
    void getCurrentRole_delegates() {
        when(staff.getCurrentRole(1, target.toString())).thenReturn("Manager");
        assertThat(svc.getCurrentRole(1, target)).isEqualTo("Manager");
    }

    // ---------- int-id overloads (structure/0004) ----------
    // Same behaviour as the String overloads, but resolve the firm directly by id
    // (getFirmById / getAnyFirmById) with no int→String→int round-trip.

    @Test
    void fireEmployee_byId_resolvesByIdAndEnds() {
        when(firms.getFirmById(1)).thenReturn(acme());
        when(firms.isProprietor(1, actor)).thenReturn(true);
        when(firms.isProprietor(1, target)).thenReturn(false);
        when(staff.endCurrentEmployment(1, target.toString(), actor.toString())).thenReturn(1);

        svc.fireEmployee(1, target, actor);

        verify(staff).endCurrentEmployment(1, target.toString(), actor.toString());
    }

    @Test
    void promoteEmployee_byId_movesToNextRole() {
        when(firms.getFirmById(1)).thenReturn(acme());
        when(firms.isProprietor(1, actor)).thenReturn(true);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(true);
        when(staff.getCurrentRole(1, target.toString())).thenReturn("Employee");
        when(roles.getRank(1, "Employee")).thenReturn(5);
        when(roles.findRoleAbove(1, 5)).thenReturn("Supervisor");
        when(staff.updateCurrentRole(1, target.toString(), "Supervisor")).thenReturn(1);

        assertThat(svc.promoteEmployee(1, target, actor)).isEqualTo("Supervisor");
    }

    @Test
    void demoteEmployee_byId_movesDown() {
        when(firms.getFirmById(1)).thenReturn(acme());
        when(firms.isProprietor(1, actor)).thenReturn(true);
        when(staff.isEmployedBy(1, target.toString())).thenReturn(true);
        when(staff.getCurrentRole(1, target.toString())).thenReturn("Manager");
        when(roles.getRank(1, "Manager")).thenReturn(3);
        when(roles.findRoleBelow(1, 3)).thenReturn("Supervisor");
        when(staff.updateCurrentRole(1, target.toString(), "Supervisor")).thenReturn(1);

        assertThat(svc.demoteEmployee(1, target, actor)).isEqualTo("Supervisor");
    }

    @Test
    void resignFromFirm_byId_endsEmployment() {
        when(firms.getFirmById(1)).thenReturn(acme());
        when(staff.isEmployedBy(1, target.toString())).thenReturn(true);

        svc.resignFromFirm(1, target);

        verify(staff).endCurrentEmployment(1, target.toString(), target.toString());
    }

    @Test
    void getCurrentEmployees_byId_prependsProprietor() {
        when(firms.getAnyFirmById(1)).thenReturn(acme());
        when(roles.findProprietorRoleName(1)).thenReturn("Boss");
        when(staff.listCurrentEmployeesByFirm(1)).thenReturn(new java.util.ArrayList<>(List.of(
                new FirmEmployee(1, target.toString(), "Employee", LocalDateTime.now(), null, actor.toString(), null, true)
        )));

        assertThat(svc.getCurrentEmployees(1)).hasSize(2);
    }

    @Test
    void getOnlineEmployees_byId_delegatesToRoster() {
        when(firms.getAnyFirmById(1)).thenReturn(acme());
        when(roles.findProprietorRoleName(1)).thenReturn("Boss");
        when(staff.listCurrentEmployeesByFirm(1)).thenReturn(new java.util.ArrayList<>());

        // Roster members are filtered by online status; all are offline in tests.
        try (var bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(() -> org.bukkit.Bukkit.getPlayer(org.mockito.ArgumentMatchers.any(UUID.class)))
                    .thenReturn(null);
            assertThat(svc.getOnlineEmployees(1)).isEmpty();
        }
    }

    @Test
    void getOnlineEmployees_byName_delegatesToRoster() {
        when(firms.getAnyFirmByNameOrId("Acme")).thenReturn(acme());
        when(roles.findProprietorRoleName(1)).thenReturn("Boss");
        when(staff.listCurrentEmployeesByFirm(1)).thenReturn(new java.util.ArrayList<>());

        try (var bukkit = org.mockito.Mockito.mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(() -> org.bukkit.Bukkit.getPlayer(org.mockito.ArgumentMatchers.any(UUID.class)))
                    .thenReturn(null);
            assertThat(svc.getOnlineEmployees("Acme")).isEmpty();
        }
    }

    @Test
    void getProprietorAsEmployee_byId_fallsBackToProprietor() {
        when(firms.getAnyFirmById(1)).thenReturn(acme());
        when(roles.findProprietorRoleName(1)).thenReturn(null);

        assertThat(svc.getProprietorAsEmployee(1).getRoleName()).isEqualTo("Proprietor");
    }
}
