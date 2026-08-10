package io.paradaux.treasuryapi.mappers;

import io.paradaux.treasuryapi.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link ExplorerUiMapper} against real MariaDB: role add/remove/list
 * (through the BINARY(16) UUID type handler), the single-use {@code claimLinkCode}
 * conditional DELETE, expiry filtering, and identity upsert.
 */
class ExplorerUiMapperIT extends IntegrationTestBase {

    private ExplorerUiMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = mapper(ExplorerUiMapper.class);
    }

    // ---- Roles (also the UuidBinaryTypeHandler round-trip) ----

    @Test
    void addRole_thenList_roundTripsThroughBinaryUuid() {
        UUID player = UUID.randomUUID();
        UUID granter = UUID.randomUUID();
        mapper.addRole(player, "admin", granter);
        mapper.addRole(player, "government", granter);

        // The list read matches on the same BINARY(16)-encoded UUID that was written —
        // proving the type handler encodes/decodes symmetrically (a byte-order bug
        // would make the WHERE never match). Ordered by role.
        assertThat(mapper.listRoles(player)).containsExactly("admin", "government");
        assertThat(mapper.listRoles(UUID.randomUUID())).isEmpty();
    }

    @Test
    void addRole_isIdempotent_andRemoveReportsAffectedRows() {
        UUID player = UUID.randomUUID();
        mapper.addRole(player, "admin", null);
        mapper.addRole(player, "admin", null); // INSERT IGNORE — still one row

        assertThat(mapper.listRoles(player)).containsExactly("admin");
        assertThat(mapper.removeRole(player, "admin")).isEqualTo(1); // removed
        assertThat(mapper.removeRole(player, "admin")).isZero();     // already gone
        assertThat(mapper.listRoles(player)).isEmpty();
    }

    // ---- Link-code single-use claim ----

    private void insertLinkCode(String code, String sub, int expiresInSeconds) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO explorer_link_code (code, keycloak_sub, expires_at) "
                             + "VALUES (?, ?, NOW() + INTERVAL ? SECOND)")) {
            ps.setString(1, code);
            ps.setString(2, sub);
            ps.setInt(3, expiresInSeconds);
            ps.executeUpdate();
        }
    }

    @Test
    void findValidLinkSub_returnsSubForUnexpired_nullForExpiredOrMissing() throws Exception {
        insertLinkCode("VALID123", "sub-1", 3600);
        insertLinkCode("EXPIRED0", "sub-2", -60); // already expired

        assertThat(mapper.findValidLinkSub("VALID123")).isEqualTo("sub-1");
        assertThat(mapper.findValidLinkSub("EXPIRED0")).isNull();
        assertThat(mapper.findValidLinkSub("MISSING0")).isNull();
    }

    @Test
    void claimLinkCode_isSingleUse_firstClaimWinsSecondGetsZero() throws Exception {
        insertLinkCode("ONCE0001", "sub-1", 3600);

        // The authoritative single-use guard: the conditional DELETE affects exactly
        // one row for the first caller, and zero thereafter — so two concurrent
        // redemptions can never both proceed.
        assertThat(mapper.claimLinkCode("ONCE0001")).isEqualTo(1);
        assertThat(mapper.claimLinkCode("ONCE0001")).isZero();
        // And the code is gone.
        assertThat(mapper.findValidLinkSub("ONCE0001")).isNull();
    }

    @Test
    void claimLinkCode_expiredCode_affectsZeroRows() throws Exception {
        insertLinkCode("STALE001", "sub-1", -60);
        assertThat(mapper.claimLinkCode("STALE001")).isZero();
    }

    // ---- Identity upsert ----

    @Test
    void upsertIdentity_insertsThenUpdatesOnDuplicateSub() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        mapper.upsertIdentity("sub-x", first, "Alice", "in-game:Alice");
        assertThat(identityPlayer("sub-x")).isEqualTo(first);
        assertThat(identityName("sub-x")).isEqualTo("Alice");

        // Same keycloak_sub re-linked to a different player updates in place (no PK clash).
        mapper.upsertIdentity("sub-x", second, "Bob", "in-game:Bob");
        assertThat(identityPlayer("sub-x")).isEqualTo(second);
        assertThat(identityName("sub-x")).isEqualTo("Bob");
    }

    private UUID identityPlayer(String sub) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT bin_to_uuid(player_uuid_bin) FROM explorer_identity WHERE keycloak_sub = ?")) {
            ps.setString(1, sub);
            try (var rs = ps.executeQuery()) { rs.next(); return UUID.fromString(rs.getString(1)); }
        }
    }

    private String identityName(String sub) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT minecraft_name FROM explorer_identity WHERE keycloak_sub = ?")) {
            ps.setString(1, sub);
            try (var rs = ps.executeQuery()) { rs.next(); return rs.getString(1); }
        }
    }
}
