package io.paradaux.business.mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmEmployee;
import io.paradaux.business.model.FirmRole;
import io.paradaux.business.testsupport.IntegrationTestBase;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FirmStaffMapperIT extends IntegrationTestBase {

    private FirmStaffMapper staff;
    private FirmRoleMapper roles;
    private FirmMapper firms;
    private int firmId;
    private final UUID actor = UUID.randomUUID();
    private final UUID employee = UUID.randomUUID();

    @BeforeEach
    void seed() {
        staff = mapper(FirmStaffMapper.class);
        roles = mapper(FirmRoleMapper.class);
        firms = mapper(FirmMapper.class);

        Firm f = new Firm();
        f.setDisplayName("Acme");
        f.setProprietorUuid(UUID.randomUUID().toString());
        firms.createFirm(f);
        firmId = f.getFirmId();

        roles.insertRole(new FirmRole(firmId, "Boss", 1));
        roles.insertRole(new FirmRole(firmId, "Worker", 5));
    }

    @Test
    void insertEmployment_returnsOneRow() {
        int rows = staff.insertEmployment(firmId, employee.toString(), "Worker", actor.toString());
        assertThat(rows).isEqualTo(1);
        assertThat(staff.isEmployedBy(firmId, employee.toString())).isTrue();
    }

    @Test
    void insertEmployment_unknownRole_zeroRows() {
        int rows = staff.insertEmployment(firmId, employee.toString(), "Ghost", actor.toString());
        assertThat(rows).isZero();
    }

    @Test
    void getCurrentRole_returnsRoleNameForCurrentEmployment() {
        staff.insertEmployment(firmId, employee.toString(), "Worker", actor.toString());
        assertThat(staff.getCurrentRole(firmId, employee.toString())).isEqualTo("Worker");
    }

    @Test
    void updateCurrentRole_changesEmployeeRole() {
        staff.insertEmployment(firmId, employee.toString(), "Worker", actor.toString());
        int updated = staff.updateCurrentRole(firmId, employee.toString(), "Boss");
        assertThat(updated).isEqualTo(1);
        assertThat(staff.getCurrentRole(firmId, employee.toString())).isEqualTo("Boss");
    }

    @Test
    void endCurrentEmployment_marksLeftAt() {
        staff.insertEmployment(firmId, employee.toString(), "Worker", actor.toString());
        int rows = staff.endCurrentEmployment(firmId, employee.toString(), actor.toString());
        assertThat(rows).isEqualTo(1);
        assertThat(staff.isEmployedBy(firmId, employee.toString())).isFalse();
    }

    @Test
    void repeatedDeparturesPreserveHistoryAndStillRejectDuplicateCurrentEmployment() throws Exception {
        staff.insertEmployment(firmId, employee.toString(), "Worker", actor.toString());
        staff.endCurrentEmployment(firmId, employee.toString(), actor.toString());
        // Separate historical start times without sleeps: joined_at is part of the primary key.
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "UPDATE firm_employee SET joined_at = joined_at - INTERVAL 1 DAY WHERE firm_id = ?")) {
            statement.setInt(1, firmId);
            statement.executeUpdate();
        }
        staff.insertEmployment(firmId, employee.toString(), "Worker", actor.toString());
        assertThat(staff.endCurrentEmployment(firmId, employee.toString(), actor.toString()))
                .isEqualTo(1);
        assertThat(staff.isEmployedBy(firmId, employee.toString())).isFalse();
        try (var connection = dataSource.getConnection();
                var statement = connection.prepareStatement(
                        "SELECT COUNT(*) FROM firm_employee WHERE firm_id = ? AND left_at IS NOT NULL")) {
            statement.setInt(1, firmId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
        }
        // A different start time avoids the primary key, exercising the current-job unique index.
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO firm_employee "
                    + "(firm_id, player_uuid_bin, role_id, joined_at, added_by_uuid_bin) "
                    + "SELECT firm_id, player_uuid_bin, role_id, joined_at + INTERVAL 2 DAY, added_by_uuid_bin "
                    + "FROM firm_employee WHERE firm_id = " + firmId + " ORDER BY joined_at DESC LIMIT 1");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO firm_employee "
                            + "(firm_id, player_uuid_bin, role_id, joined_at, added_by_uuid_bin) "
                            + "SELECT firm_id, player_uuid_bin, role_id, joined_at + INTERVAL 1 DAY, added_by_uuid_bin "
                            + "FROM firm_employee WHERE firm_id = " + firmId + " AND left_at IS NULL"))
                    .isInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class)
                    .hasMessageContaining("uq_emp_one_current");
        }
    }

    @Test
    void listCurrentEmployeesByFirm_returnsActiveOnly() {
        UUID e2 = UUID.randomUUID();
        staff.insertEmployment(firmId, employee.toString(), "Worker", actor.toString());
        staff.insertEmployment(firmId, e2.toString(), "Worker", actor.toString());
        staff.endCurrentEmployment(firmId, e2.toString(), actor.toString());

        List<FirmEmployee> emps = staff.listCurrentEmployeesByFirm(firmId);
        assertThat(emps)
                .extracting(FirmEmployee::getPlayerUuid)
                .extracting(s -> s.toLowerCase(Locale.ROOT))
                .containsExactly(employee.toString());
    }
}
