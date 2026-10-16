package io.paradaux.business.mappers;

import static org.assertj.core.api.Assertions.assertThat;

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
