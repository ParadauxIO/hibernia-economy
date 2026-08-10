package io.paradaux.treasuryapi.mappers;

import io.paradaux.treasuryapi.model.economy.ApiKey;
import io.paradaux.treasuryapi.model.economy.KeyType;
import io.paradaux.treasuryapi.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link ApiKeyMapper} against real MariaDB (economy-flyway schema):
 * insert + generated key, the revoke-terminal reissue guard ({@code AND revoked = 0}),
 * the owner/type list query, and the employee-access JOIN.
 */
class ApiKeyMapperIT extends IntegrationTestBase {

    private ApiKeyMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = mapper(ApiKeyMapper.class);
    }

    private int insertAccount() throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO accounts (account_type, owner_uuid_bin, display_name) "
                             + "VALUES ('PERSONAL', uuid_to_bin(?), 'acct')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) { rs.next(); return rs.getInt(1); }
        }
    }

    private int insertFirm(String name, UUID owner) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO firm (display_name, proprietor_uuid_bin) VALUES (?, uuid_to_bin(?))",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, owner.toString());
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) { rs.next(); return rs.getInt(1); }
        }
    }

    private void employ(int firmId, UUID player, UUID by) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("INSERT INTO firm_role (firm_id, name, rank_order) VALUES ("
                    + firmId + ", 'Staff', 1)");
            int roleId;
            try (var rs = st.executeQuery("SELECT role_id FROM firm_role WHERE firm_id = " + firmId)) {
                rs.next(); roleId = rs.getInt(1);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO firm_employee (firm_id, player_uuid_bin, role_id, added_by_uuid_bin) "
                            + "VALUES (?, uuid_to_bin(?), ?, uuid_to_bin(?))")) {
                ps.setInt(1, firmId);
                ps.setString(2, player.toString());
                ps.setInt(3, roleId);
                ps.setString(4, by.toString());
                ps.executeUpdate();
            }
        }
    }

    private ApiKey personalKey(int accountId, UUID owner) {
        ApiKey k = new ApiKey();
        k.setKeyType(KeyType.PERSONAL);
        k.setAccountId(accountId);
        k.setOwnerUuid(owner);
        k.setJwtId(UUID.randomUUID().toString());
        k.setIssuedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        k.setExpiresAt(Instant.now().plus(180, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS));
        return k;
    }

    @Test
    void insert_setsGeneratedKey_andFindByIdRoundTrips() throws Exception {
        int account = insertAccount();
        UUID owner = UUID.randomUUID();
        ApiKey k = personalKey(account, owner);

        mapper.insert(k);
        assertThat(k.getKeyId()).isPositive();

        ApiKey found = mapper.findById(k.getKeyId());
        assertThat(found.getKeyType()).isEqualTo(KeyType.PERSONAL);
        assertThat(found.getAccountId()).isEqualTo(account);
        assertThat(found.getFirmId()).isNull();
        assertThat(found.getOwnerUuid()).isEqualTo(owner);
        assertThat(found.getJwtId()).isEqualTo(k.getJwtId());
        assertThat(found.isRevoked()).isFalse();
    }

    @Test
    void reissue_updatesJwtId_whenActive() throws Exception {
        int account = insertAccount();
        ApiKey k = personalKey(account, UUID.randomUUID());
        mapper.insert(k);

        String newJwt = UUID.randomUUID().toString();
        Instant now = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        int rows = mapper.reissue(k.getKeyId(), newJwt, now, now.plus(180, ChronoUnit.DAYS));

        assertThat(rows).isEqualTo(1);
        assertThat(mapper.findById(k.getKeyId()).getJwtId()).isEqualTo(newJwt);
    }

    @Test
    void reissue_revokedKey_affectsZeroRows_revocationIsTerminal() throws Exception {
        // The `AND revoked = 0` SQL guard is the DB-level enforcement of "revoked
        // stays revoked": once revoked, reissue must touch no rows.
        int account = insertAccount();
        ApiKey k = personalKey(account, UUID.randomUUID());
        mapper.insert(k);
        assertThat(mapper.revoke(k.getKeyId())).isEqualTo(1);

        int rows = mapper.reissue(k.getKeyId(), UUID.randomUUID().toString(),
                Instant.now(), Instant.now().plus(180, ChronoUnit.DAYS));

        assertThat(rows).isZero();
        assertThat(mapper.findById(k.getKeyId()).isRevoked()).isTrue();
    }

    @Test
    void findByOwnerAndType_scopesToOwnerAndType_ordered() throws Exception {
        int account = insertAccount();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ApiKey k1 = personalKey(account, owner); mapper.insert(k1);
        ApiKey k2 = personalKey(account, owner); mapper.insert(k2);
        ApiKey foreign = personalKey(account, other); mapper.insert(foreign);

        List<ApiKey> mine = mapper.findByOwnerAndType(owner, KeyType.PERSONAL);
        assertThat(mine).extracting(ApiKey::getKeyId)
                .containsExactly(k1.getKeyId(), k2.getKeyId()); // ordered by key_id
        assertThat(mapper.findByOwnerAndType(owner, KeyType.BUSINESS)).isEmpty();
        assertThat(mapper.findByOwnerAndType(other, KeyType.PERSONAL))
                .extracting(ApiKey::getKeyId).containsExactly(foreign.getKeyId());
    }

    @Test
    void findBusinessAccessibleByEmployee_returnsFirmKeysForCurrentEmployeesOnly() throws Exception {
        UUID proprietor = UUID.randomUUID();
        UUID employee = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        int firmId = insertFirm("Acme", proprietor);
        employ(firmId, employee, proprietor);

        ApiKey bizKey = new ApiKey();
        bizKey.setKeyType(KeyType.BUSINESS);
        bizKey.setFirmId(firmId);
        bizKey.setOwnerUuid(proprietor);
        bizKey.setJwtId(UUID.randomUUID().toString());
        bizKey.setIssuedAt(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        bizKey.setExpiresAt(Instant.now().plus(180, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS));
        mapper.insert(bizKey);

        // An employee of the firm sees the firm's BUSINESS key via the JOIN...
        assertThat(mapper.findBusinessAccessibleByEmployee(employee))
                .extracting(ApiKey::getKeyId).containsExactly(bizKey.getKeyId());
        // ...but a non-employee sees nothing.
        assertThat(mapper.findBusinessAccessibleByEmployee(stranger)).isEmpty();
    }
}
