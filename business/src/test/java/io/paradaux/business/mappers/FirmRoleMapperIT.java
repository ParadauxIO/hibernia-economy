package io.paradaux.business.mappers;

import static org.assertj.core.api.Assertions.assertThat;

import io.paradaux.business.model.RolePermission;
import io.paradaux.business.model.internal.Firm;
import io.paradaux.business.model.internal.FirmRole;
import io.paradaux.business.model.internal.FirmRolePermission;
import io.paradaux.business.testsupport.IntegrationTestBase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FirmRoleMapperIT extends IntegrationTestBase {

    private FirmRoleMapper mapper;
    private FirmMapper firms;
    private int firmId;

    @BeforeEach
    void openSession() {
        mapper = mapper(FirmRoleMapper.class);
        firms = mapper(FirmMapper.class);

        Firm firm = new Firm();
        firm.setDisplayName("Acme");
        firm.setProprietorUuid(UUID.randomUUID().toString());
        firms.createFirm(firm);
        firmId = firm.getFirmId();
    }

    @Test
    void insertAndListRoles_orderedByRank() {
        mapper.insertRole(new FirmRole(firmId, "Boss", 1));
        mapper.insertRole(new FirmRole(firmId, "Manager", 3));
        mapper.insertRole(new FirmRole(firmId, "Worker", 5));

        List<FirmRole> roles = mapper.listRolesByFirm(firmId);
        assertThat(roles).extracting(FirmRole::getRoleName).containsExactly("Boss", "Manager", "Worker");
    }

    @Test
    void deleteRole_returnsRowCount() {
        mapper.insertRole(new FirmRole(firmId, "Lead", 2));
        assertThat(mapper.deleteRole(firmId, "Lead")).isEqualTo(1);
        assertThat(mapper.deleteRole(firmId, "Lead")).isZero();
    }

    @Test
    void renameRole_persistsNewName() {
        mapper.insertRole(new FirmRole(firmId, "Lead", 2));
        assertThat(mapper.renameRole(firmId, "Lead", "Leader")).isEqualTo(1);
        assertThat(mapper.listRolesByFirm(firmId))
                .extracting(FirmRole::getRoleName)
                .containsExactly("Leader");
    }

    @Test
    void updateRoleRank_changesOrder() {
        mapper.insertRole(new FirmRole(firmId, "A", 1));
        mapper.insertRole(new FirmRole(firmId, "B", 2));
        mapper.updateRoleRank(firmId, "A", 3);
        // A is now rank 3, but B is rank 2 -> the unique uq_role_rank constraint is satisfied
        assertThat(mapper.getRank(firmId, "A")).isEqualTo(3);
        assertThat(mapper.getRank(firmId, "B")).isEqualTo(2);
    }

    @Test
    void findProprietorRoleName_returnsLowestRank() {
        mapper.insertRole(new FirmRole(firmId, "Boss", 1));
        mapper.insertRole(new FirmRole(firmId, "Worker", 5));
        assertThat(mapper.findProprietorRoleName(firmId)).isEqualTo("Boss");
    }

    @Test
    void findMinAndLowestRoleHelpers() {
        mapper.insertRole(new FirmRole(firmId, "Boss", 1));
        mapper.insertRole(new FirmRole(firmId, "Worker", 5));
        assertThat(mapper.findMinRankOrder(firmId)).isEqualTo(1);
        assertThat(mapper.findLowestRole(firmId)).isEqualTo("Worker");
    }

    @Test
    void findRoleAboveAndBelow() {
        mapper.insertRole(new FirmRole(firmId, "Boss", 1));
        mapper.insertRole(new FirmRole(firmId, "Manager", 3));
        mapper.insertRole(new FirmRole(firmId, "Worker", 5));

        assertThat(mapper.findRoleBelow(firmId, 3)).isEqualTo("Worker");
        assertThat(mapper.findRoleBelow(firmId, 5)).isNull(); // already lowest
        assertThat(mapper.findRoleAbove(firmId, 5)).isEqualTo("Manager");
        // findRoleAbove excludes the proprietor role (lowest rank), so from rank 3 it's null:
        assertThat(mapper.findRoleAbove(firmId, 3)).isNull();
    }

    @Test
    void roleExists_returnsTrueWhenPresent() {
        mapper.insertRole(new FirmRole(firmId, "Boss", 1));
        assertThat(mapper.roleExists(firmId, "Boss")).isEqualTo(1);
        assertThat(mapper.roleExists(firmId, "Ghost")).isNull();
    }

    @Test
    void addAndListRolePermissions() {
        mapper.insertRole(new FirmRole(firmId, "Lead", 2));
        mapper.addRolePermission(new FirmRolePermission(firmId, "Lead", RolePermission.ADMIN));
        mapper.addRolePermission(new FirmRolePermission(firmId, "Lead", RolePermission.FINANCIAL));

        List<FirmRolePermission> perms = mapper.listPermissionsByRole(firmId, "Lead");
        assertThat(perms)
                .extracting(FirmRolePermission::getPermission)
                .containsExactlyInAnyOrder(RolePermission.ADMIN, RolePermission.FINANCIAL);
    }

    @Test
    void deleteRolePermission_returnsRowCount() {
        mapper.insertRole(new FirmRole(firmId, "Lead", 2));
        mapper.addRolePermission(new FirmRolePermission(firmId, "Lead", RolePermission.ADMIN));

        assertThat(mapper.deleteRolePermission(firmId, "Lead", RolePermission.ADMIN))
                .isEqualTo(1);
        assertThat(mapper.deleteRolePermission(firmId, "Lead", RolePermission.ADMIN))
                .isZero();
    }

    @Test
    void getFirmRolesAndPermissions_aliases() {
        mapper.insertRole(new FirmRole(firmId, "Lead", 2));
        mapper.addRolePermission(new FirmRolePermission(firmId, "Lead", RolePermission.ADMIN));

        assertThat(mapper.getFirmRoles(firmId)).hasSize(1);
        assertThat(mapper.getFirmRolePermissions(firmId, "Lead"))
                .extracting(FirmRolePermission::getPermission)
                .containsExactly(RolePermission.ADMIN);
    }
}
