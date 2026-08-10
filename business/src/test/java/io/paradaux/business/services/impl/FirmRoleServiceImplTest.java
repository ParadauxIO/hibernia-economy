package io.paradaux.business.services.impl;

import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.business.mappers.FirmRoleMapper;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmRole;
import io.paradaux.business.model.FirmRolePermission;
import io.paradaux.business.model.RolePermission;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirmRoleServiceImplTest {

    @Mock FirmService firms;
    @Mock FirmStaffService staffService;
    @Mock FirmRoleMapper roles;
    @Mock FirmAccountService accounts;

    private FirmRoleServiceImpl svc;
    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        svc = new FirmRoleServiceImpl(firms, staffService, roles, accounts);
    }

    private Firm firm(int id) {
        Firm f = new Firm();
        f.setFirmId(id);
        f.setDisplayName("Acme");
        return f;
    }

    // ---------- createRole ----------

    @Test
    void createRole_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.createRole("Ghost", "X", 5, actor))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createRole_requiresAdminPermission() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(false);
        assertThatThrownBy(() -> svc.createRole("Acme", "X", 5, actor))
                .isInstanceOf(NoPermissionException.class);
    }

    @Test
    void createRole_rejectsRankAtOrAboveProprietor() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.findMinRankOrder(1)).thenReturn(2);
        assertThatThrownBy(() -> svc.createRole("Acme", "Boss", 2, actor))
                .isInstanceOf(BadCommandException.class);
        assertThatThrownBy(() -> svc.createRole("Acme", "Boss", 1, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void createRole_normalizesNameAndInserts() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.findMinRankOrder(1)).thenReturn(1);
        when(roles.insertRole(any())).thenReturn(1);

        svc.createRole("Acme", "  Lead  ", 3, actor);

        ArgumentCaptor<FirmRole> cap = ArgumentCaptor.forClass(FirmRole.class);
        verify(roles).insertRole(cap.capture());
        assertThat(cap.getValue().getRoleName()).isEqualTo("Lead");
        assertThat(cap.getValue().getRoleRankOrder()).isEqualTo(3);
        assertThat(cap.getValue().getFirmId()).isEqualTo(1);

        // The baseline DEFAULT permission is granted atomically (ADT-56).
        ArgumentCaptor<FirmRolePermission> permCap = ArgumentCaptor.forClass(FirmRolePermission.class);
        verify(roles).addRolePermission(permCap.capture());
        assertThat(permCap.getValue().getRoleName()).isEqualTo("Lead");
        assertThat(permCap.getValue().getPermission()).isEqualTo(RolePermission.DEFAULT);
    }

    @Test
    void createRole_duplicateMappedToConflict() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.findMinRankOrder(1)).thenReturn(1);
        when(roles.insertRole(any())).thenThrow(new PersistenceException("dup"));
        assertThatThrownBy(() -> svc.createRole("Acme", "Lead", 3, actor))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void createRole_zeroRowsThrowsIllegalState() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.findMinRankOrder(1)).thenReturn(1);
        when(roles.insertRole(any())).thenReturn(0);
        assertThatThrownBy(() -> svc.createRole("Acme", "Lead", 3, actor))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- deleteRole ----------

    @Test
    void deleteRole_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.deleteRole("Ghost", "X", actor))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteRole_protectsProprietorRole() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.findProprietorRoleName(1)).thenReturn("Proprietor");
        assertThatThrownBy(() -> svc.deleteRole("Acme", "proprietor", actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void deleteRole_missingRow_throwsNotFound() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.findProprietorRoleName(1)).thenReturn("Proprietor");
        when(roles.deleteRole(1, "Lead")).thenReturn(0);
        assertThatThrownBy(() -> svc.deleteRole("Acme", "Lead", actor))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteRole_succeeds() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.findProprietorRoleName(1)).thenReturn("Proprietor");
        when(roles.deleteRole(1, "Lead")).thenReturn(1);
        svc.deleteRole("Acme", "Lead", actor);
        verify(roles).deleteRole(1, "Lead");
        verify(accounts).syncAllFirmAccounts(1);
    }

    @Test
    void deleteRole_requiresAdminPermission() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(false);
        assertThatThrownBy(() -> svc.deleteRole("Acme", "Lead", actor))
                .isInstanceOf(NoPermissionException.class);
    }

    // ---------- renameRole ----------

    @Test
    void renameRole_targetMissing_throwsNotFound() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.listRolesByFirm(1)).thenReturn(List.of());
        assertThatThrownBy(() -> svc.renameRole("Acme", "Lead", "Boss", actor))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void renameRole_succeeds() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.listRolesByFirm(1)).thenReturn(List.of(new FirmRole(1, "Lead", 3)));
        when(roles.renameRole(1, "Lead", "Boss")).thenReturn(1);
        svc.renameRole("Acme", "Lead", "Boss", actor);
        verify(roles).renameRole(1, "Lead", "Boss");
        verify(accounts).syncAllFirmAccounts(1);
    }

    @Test
    void renameRole_dupMappedToConflict() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.listRolesByFirm(1)).thenReturn(List.of(new FirmRole(1, "Lead", 3)));
        when(roles.renameRole(1, "Lead", "Boss")).thenThrow(new PersistenceException("dup"));
        assertThatThrownBy(() -> svc.renameRole("Acme", "Lead", "Boss", actor))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void renameRole_zeroRowsThrowsIllegalState() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.listRolesByFirm(1)).thenReturn(List.of(new FirmRole(1, "Lead", 3)));
        when(roles.renameRole(1, "Lead", "Boss")).thenReturn(0);
        assertThatThrownBy(() -> svc.renameRole("Acme", "Lead", "Boss", actor))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- addRolePermission ----------

    @Test
    void addRolePermission_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.addRolePermission("Ghost", "Lead", "ADMIN", actor))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void addRolePermission_unknownRole_throws() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.listRolesByFirm(1)).thenReturn(List.of());
        assertThatThrownBy(() -> svc.addRolePermission("Acme", "Lead", "ADMIN", actor))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void addRolePermission_invalidPermission_throws() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.listRolesByFirm(1)).thenReturn(List.of(new FirmRole(1, "Lead", 3)));
        assertThatThrownBy(() -> svc.addRolePermission("Acme", "Lead", "WAT", actor))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addRolePermission_succeeds() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.listRolesByFirm(1)).thenReturn(List.of(new FirmRole(1, "Lead", 3)));

        svc.addRolePermission("Acme", "Lead", "FINANCIAL", actor);

        ArgumentCaptor<FirmRolePermission> cap = ArgumentCaptor.forClass(FirmRolePermission.class);
        verify(roles).addRolePermission(cap.capture());
        verify(accounts).syncAllFirmAccounts(1);
        assertThat(cap.getValue().getPermission()).isEqualTo(RolePermission.FINANCIAL);
        assertThat(cap.getValue().getRoleName()).isEqualTo("Lead");
    }

    @Test
    void addRolePermission_dupMappedToConflict() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.listRolesByFirm(1)).thenReturn(List.of(new FirmRole(1, "Lead", 3)));
        when(roles.addRolePermission(any())).thenThrow(new PersistenceException("dup"));
        assertThatThrownBy(() -> svc.addRolePermission("Acme", "Lead", "FINANCIAL", actor))
                .isInstanceOf(ConflictException.class);
    }

    // ---------- removeRolePermission ----------

    @Test
    void removeRolePermission_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.removeRolePermission("Ghost", "Lead", "ADMIN", actor))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void removeRolePermission_zeroRowsThrowsNotFound() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.deleteRolePermission(1, "Lead", RolePermission.FINANCIAL)).thenReturn(0);
        assertThatThrownBy(() -> svc.removeRolePermission("Acme", "Lead", "FINANCIAL", actor))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void removeRolePermission_succeeds() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.deleteRolePermission(1, "Lead", RolePermission.FINANCIAL)).thenReturn(1);
        svc.removeRolePermission("Acme", "Lead", "FINANCIAL", actor);
        verify(roles).deleteRolePermission(1, "Lead", RolePermission.FINANCIAL);
        verify(accounts).syncAllFirmAccounts(1);
    }

    // ---------- getters ----------

    @Test
    void getFirmRoles_byName_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.getFirmRoles("Ghost", actor))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getFirmRoles_byName_requiresAdmin() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(false);
        assertThatThrownBy(() -> svc.getFirmRoles("Acme", actor))
                .isInstanceOf(NoPermissionException.class);
    }

    @Test
    void getFirmRoles_byName_returnsRoles() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.getFirmRoles(1)).thenReturn(List.of(new FirmRole(1, "Lead", 3)));
        assertThat(svc.getFirmRoles("Acme", actor)).hasSize(1);
    }

    @Test
    void getFirmRoles_byId_doesNotAuthCheck() {
        when(roles.getFirmRoles(1)).thenReturn(List.of(new FirmRole(1, "Lead", 3)));
        assertThat(svc.getFirmRoles(1)).hasSize(1);
        verify(staffService, never()).hasPermission(anyInt(), any(), any());
    }

    @Test
    void getFirmRolePermissions_byName_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.getFirmRolePermissions("Ghost", "Lead", actor))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getFirmRolePermissions_byName_returnsList() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.getFirmRolePermissions(1, "Lead")).thenReturn(List.of(
                new FirmRolePermission(1, "Lead", RolePermission.ADMIN)
        ));
        assertThat(svc.getFirmRolePermissions("Acme", "Lead", actor)).hasSize(1);
    }

    @Test
    void getFirmRolePermissions_byId_doesNotAuthCheck() {
        when(roles.getFirmRolePermissions(1, "Lead")).thenReturn(List.of());
        assertThat(svc.getFirmRolePermissions(1, "Lead")).isEmpty();
    }

    // ---------- int-id overloads (structure/0004) ----------
    // Same behaviour as the String overloads, resolving the firm by id
    // (getFirmById) rather than round-tripping through getFirmByNameOrId.

    @Test
    void createRole_byId_inserts() {
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.findMinRankOrder(1)).thenReturn(1);
        when(roles.insertRole(any())).thenReturn(1);

        svc.createRole(1, "Lead", 3, actor);

        verify(roles).insertRole(any());
    }

    @Test
    void deleteRole_byId_succeeds() {
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.findProprietorRoleName(1)).thenReturn("Proprietor");
        when(roles.deleteRole(1, "Lead")).thenReturn(1);

        svc.deleteRole(1, "Lead", actor);

        verify(roles).deleteRole(1, "Lead");
    }

    @Test
    void addRolePermission_byId_succeeds() {
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.listRolesByFirm(1)).thenReturn(List.of(new FirmRole(1, "Lead", 3)));

        svc.addRolePermission(1, "Lead", "FINANCIAL", actor);

        verify(roles).addRolePermission(any());
    }

    @Test
    void removeRolePermission_byId_succeeds() {
        when(firms.getFirmById(1)).thenReturn(firm(1));
        when(staffService.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(roles.deleteRolePermission(1, "Lead", RolePermission.FINANCIAL)).thenReturn(1);

        svc.removeRolePermission(1, "Lead", "FINANCIAL", actor);

        verify(roles).deleteRolePermission(1, "Lead", RolePermission.FINANCIAL);
    }
}
