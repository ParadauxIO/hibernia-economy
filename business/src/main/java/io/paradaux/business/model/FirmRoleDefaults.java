package io.paradaux.business.model;


import java.util.List;

public class FirmRoleDefaults {

    public static List<FirmRole> getDefaultRoles(Integer firmId) {
        return List.of(
                new FirmRole(firmId, "Proprietor", 1),
                new FirmRole(firmId, "Co-Proprietor", 2),
                new FirmRole(firmId, "Manager", 3),
                new FirmRole(firmId, "Supervisor", 4),
                new FirmRole(firmId, "Employee", 5)
        );
    }

    public static List<FirmRolePermission> getDefaultPermissions(Integer firmId) {
        return List.of(
                new FirmRolePermission(firmId, "Proprietor", RolePermission.ADMIN),
                new FirmRolePermission(firmId, "Co-Proprietor", RolePermission.ADMIN),
                new FirmRolePermission(firmId, "Manager", RolePermission.FINANCIAL),
                new FirmRolePermission(firmId, "Supervisor", RolePermission.CHESTSHOP),
                new FirmRolePermission(firmId, "Employee", RolePermission.DEFAULT)
        );
    }
}
