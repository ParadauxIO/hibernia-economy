package io.paradaux.business.mappers;

import io.paradaux.business.model.Firm;
import io.paradaux.business.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FirmMapperIT extends IntegrationTestBase {

    private FirmMapper mapper;

    @BeforeEach
    void openSession() {
        mapper = mapper(FirmMapper.class);
    }

    @Test
    void createFirm_setsGeneratedKey() {
        Firm firm = newFirm("Acme", UUID.randomUUID());
        mapper.createFirm(firm);
        assertThat(firm.getFirmId()).isPositive();
    }

    @Test
    void getFirmById_returnsRow() {
        UUID owner = UUID.randomUUID();
        Firm firm = newFirm("Acme", owner);
        mapper.createFirm(firm);

        Firm fetched = mapper.getFirmById(firm.getFirmId());
        assertThat(fetched.getDisplayName()).isEqualTo("Acme");
        assertThat(fetched.getProprietorUuid()).isEqualToIgnoringCase(owner.toString());
        assertThat(fetched.getArchived()).isFalse();
    }

    @Test
    void getFirmByName_isCaseInsensitive() {
        // The display_name column inherits the table's utf8mb4_unicode_ci collation,
        // so equality matches case-insensitively. The unique constraint follows the
        // same rule, which is why creating "Acme" blocks "ACME" too.
        Firm firm = newFirm("Acme", UUID.randomUUID());
        mapper.createFirm(firm);
        assertThat(mapper.getFirmByName("Acme")).isNotNull();
        assertThat(mapper.getFirmByName("ACME")).isNotNull();
        assertThat(mapper.getFirmByName("Other")).isNull();
    }

    @Test
    void updateFirm_dynamicSetIgnoresNullColumns() {
        UUID owner = UUID.randomUUID();
        Firm firm = newFirm("Acme", owner);
        mapper.createFirm(firm);

        Firm patch = new Firm();
        patch.setFirmId(firm.getFirmId());
        patch.setHqRegion("plot-1");
        mapper.updateFirm(patch);

        Firm after = mapper.getFirmById(firm.getFirmId());
        assertThat(after.getHqRegion()).isEqualTo("plot-1");
        assertThat(after.getDisplayName()).isEqualTo("Acme"); // not touched
        assertThat(after.getProprietorUuid()).isEqualToIgnoringCase(owner.toString());
    }

    @Test
    void updateFirm_persistsDefaultAccountId() throws Exception {
        int accountId = insertStubAccount("Acme Corporate");
        Firm firm = newFirm("Acme", UUID.randomUUID());
        mapper.createFirm(firm);

        Firm patch = new Firm();
        patch.setFirmId(firm.getFirmId());
        patch.setDefaultAccountId(accountId);
        mapper.updateFirm(patch);

        // Writer (updateFirm) and reader (getFirmById) must agree on default_account_id
        // — the SQL-level guarantee behind setdefault / self-heal (PAR-62).
        assertThat(mapper.getFirmById(firm.getFirmId()).getDefaultAccountId()).isEqualTo(accountId);
    }

    @Test
    void updateFirm_archive_flipsFlag() {
        Firm firm = newFirm("Acme", UUID.randomUUID());
        mapper.createFirm(firm);

        Firm patch = new Firm();
        patch.setFirmId(firm.getFirmId());
        patch.setArchived(true);
        mapper.updateFirm(patch);

        assertThat(mapper.getFirmById(firm.getFirmId()).getArchived()).isTrue();
    }

    @Test
    void getFirmsOwnedByCount_excludesArchived() {
        UUID owner = UUID.randomUUID();
        Firm a = newFirm("A", owner); mapper.createFirm(a);
        Firm b = newFirm("B", owner); mapper.createFirm(b);

        Firm patch = new Firm();
        patch.setFirmId(a.getFirmId());
        patch.setArchived(true);
        mapper.updateFirm(patch);

        assertThat(mapper.getFirmsOwnedByCount(owner.toString())).isEqualTo(1);
    }

    @Test
    void getFirmsByNameCount_returnsCount() {
        mapper.createFirm(newFirm("Acme", UUID.randomUUID()));
        assertThat(mapper.getFirmsByNameCount("Acme")).isEqualTo(1);
        assertThat(mapper.getFirmsByNameCount("Nope")).isZero();
    }

    @Test
    void isProprietor_byNameAndId() {
        UUID owner = UUID.randomUUID();
        Firm firm = newFirm("Acme", owner);
        mapper.createFirm(firm);

        assertThat(mapper.isProprietorByFirmName("Acme", owner.toString())).isTrue();
        assertThat(mapper.isProprietorByFirmId(firm.getFirmId(), owner.toString())).isTrue();
        assertThat(mapper.isProprietorByFirmName("Acme", UUID.randomUUID().toString())).isFalse();
    }

    @Test
    void listAllFiltered_excludesArchivedByDefault() {
        Firm a = newFirm("A", UUID.randomUUID()); mapper.createFirm(a);
        Firm b = newFirm("B", UUID.randomUUID()); mapper.createFirm(b);
        Firm patch = new Firm();
        patch.setFirmId(a.getFirmId());
        patch.setArchived(true);
        mapper.updateFirm(patch);

        List<Firm> active = mapper.listAllFiltered(10, 0, false);
        assertThat(active).extracting(Firm::getDisplayName).containsExactly("B");

        List<Firm> withArchived = mapper.listAllFiltered(10, 0, true);
        assertThat(withArchived).extracting(Firm::getDisplayName).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void listAllActive_returnsOnlyNotArchived() {
        Firm a = newFirm("A", UUID.randomUUID()); mapper.createFirm(a);
        Firm b = newFirm("B", UUID.randomUUID()); mapper.createFirm(b);
        Firm patch = new Firm();
        patch.setFirmId(a.getFirmId());
        patch.setArchived(true);
        mapper.updateFirm(patch);

        assertThat(mapper.listAllActive())
                .extracting(Firm::getDisplayName)
                .containsExactly("B");
    }

    @Test
    void listOwnedOrMemberFirms_includesOwned_andEmployment() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        Firm owned = newFirm("Owned", owner);
        mapper.createFirm(owned);

        Firm employer = newFirm("Employer", UUID.randomUUID());
        mapper.createFirm(employer);

        // Insert a role + employment row directly so the OR-branch fires.
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement()) {
            st.execute("INSERT INTO firm_role (firm_id, name, rank_order) VALUES ("
                    + employer.getFirmId() + ", 'Employee', 1)");
            try (var rs = st.executeQuery(
                    "SELECT role_id FROM firm_role WHERE firm_id = " + employer.getFirmId())) {
                rs.next();
                int roleId = rs.getInt(1);
                try (var ps = conn.prepareStatement(
                        "INSERT INTO firm_employee (firm_id, player_uuid_bin, role_id, added_by_uuid_bin) " +
                        "VALUES (?, uuid_to_bin(?), ?, uuid_to_bin(?))")) {
                    ps.setInt(1, employer.getFirmId());
                    ps.setString(2, employee.toString());
                    ps.setInt(3, roleId);
                    ps.setString(4, owner.toString());
                    ps.executeUpdate();
                }
            }
        }

        assertThat(mapper.listOwnedOrMemberFirms(owner.toString()))
                .extracting(Firm::getDisplayName)
                .containsExactly("Owned");
        assertThat(mapper.listOwnedOrMemberFirms(employee.toString()))
                .extracting(Firm::getDisplayName)
                .containsExactly("Employer");
    }

    @Test
    void updateFirm_advancesUpdatedAt() throws Exception {
        Firm firm = newFirm("Acme", UUID.randomUUID());
        mapper.createFirm(firm);

        // Force updated_at into the past so the bump is unambiguous without a sleep.
        // (updateFirm used to write `updated_at = updated_at`, which self-assigns the
        // column and suppresses the ON UPDATE CURRENT_TIMESTAMP bump — PAR-315.)
        java.time.LocalDateTime past = java.time.LocalDateTime.of(2020, 1, 1, 0, 0, 0);
        try (var conn = dataSource.getConnection();
             var st = conn.createStatement()) {
            st.executeUpdate("UPDATE firm SET updated_at = '2020-01-01 00:00:00' WHERE firm_id = "
                    + firm.getFirmId());
        }
        assertThat(mapper.getFirmById(firm.getFirmId()).getUpdatedAt()).isEqualTo(past);

        Firm patch = new Firm();
        patch.setFirmId(firm.getFirmId());
        patch.setHqRegion("plot-9");
        mapper.updateFirm(patch);

        assertThat(mapper.getFirmById(firm.getFirmId()).getUpdatedAt()).isAfter(past);
    }

    private Firm newFirm(String name, UUID owner) {
        Firm f = new Firm();
        f.setDisplayName(name);
        f.setProprietorUuid(owner.toString());
        return f;
    }
}
