package io.paradaux.business.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FirmRoleDefaultsTest {

    @Test
    void defaultRolesAreOrderedByRank() {
        List<FirmRole> roles = FirmRoleDefaults.getDefaultRoles(7);

        assertThat(roles).extracting(FirmRole::getRoleName).containsExactly(
                "Proprietor", "Co-Proprietor", "Manager", "Supervisor", "Employee");
        assertThat(roles).extracting(FirmRole::getRoleRankOrder).containsExactly(1, 2, 3, 4, 5);
        assertThat(roles).allSatisfy(r -> assertThat(r.getFirmId()).isEqualTo(7));
    }

    @Test
    void defaultPermissionsCoverEveryDefaultRole() {
        List<FirmRolePermission> perms = FirmRoleDefaults.getDefaultPermissions(7);
        List<String> roleNames = FirmRoleDefaults.getDefaultRoles(7).stream().map(FirmRole::getRoleName).toList();

        assertThat(perms).hasSize(roleNames.size());
        assertThat(perms).extracting(FirmRolePermission::getRoleName)
                .containsExactlyInAnyOrderElementsOf(roleNames);
        assertThat(perms).allSatisfy(p -> assertThat(p.getFirmId()).isEqualTo(7));
    }

    @Test
    void proprietorAndCoProprietorAreAdmins() {
        List<FirmRolePermission> perms = FirmRoleDefaults.getDefaultPermissions(1);
        assertThat(perms).filteredOn(p -> p.getRoleName().equals("Proprietor"))
                .singleElement().extracting(FirmRolePermission::getPermission).isEqualTo(RolePermission.ADMIN);
        assertThat(perms).filteredOn(p -> p.getRoleName().equals("Co-Proprietor"))
                .singleElement().extracting(FirmRolePermission::getPermission).isEqualTo(RolePermission.ADMIN);
    }

    @Test
    void managerHasFinancialPermission() {
        List<FirmRolePermission> perms = FirmRoleDefaults.getDefaultPermissions(1);
        assertThat(perms).filteredOn(p -> p.getRoleName().equals("Manager"))
                .singleElement().extracting(FirmRolePermission::getPermission).isEqualTo(RolePermission.FINANCIAL);
    }

    @Test
    void supervisorHasChestshopPermission() {
        List<FirmRolePermission> perms = FirmRoleDefaults.getDefaultPermissions(1);
        assertThat(perms).filteredOn(p -> p.getRoleName().equals("Supervisor"))
                .singleElement().extracting(FirmRolePermission::getPermission).isEqualTo(RolePermission.CHESTSHOP);
    }

    @Test
    void employeeHasDefaultPermissionOnly() {
        List<FirmRolePermission> perms = FirmRoleDefaults.getDefaultPermissions(1);
        assertThat(perms).filteredOn(p -> p.getRoleName().equals("Employee"))
                .singleElement().extracting(FirmRolePermission::getPermission).isEqualTo(RolePermission.DEFAULT);
    }
}
