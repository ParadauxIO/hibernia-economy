package io.paradaux.treasury.mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paradaux.hibernia.testsupport.db.SkipLedgerInvariants;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Exercises the migrated constraints through independent SQL writers, not service mocks. */
@SkipLedgerInvariants("Schema-only fixtures have no ledger or materialized balances")
class GlobalNameNamespaceIT extends IntegrationTestBase {
    private static final String OWNER = "11111111111111111111111111111111";
    private static final String OTHER = "22222222222222222222222222222222";

    @ParameterizedTest
    @CsvSource({
        "ACCOUNT,FIRM",
        "ACCOUNT,PLAYER",
        "FIRM,ACCOUNT",
        "FIRM,PLAYER",
        "PLAYER,ACCOUNT",
        "PLAYER,FIRM",
        "ACCOUNT,ACCOUNT",
        "FIRM,FIRM",
        "PLAYER,PLAYER"
    })
    void rejectsCreateAcrossEveryKindAndPreservesTheWinner(String first, String second) throws Exception {
        sql(create(first, "Reserved", OWNER));
        assertThatThrownBy(() -> sql(create(second, " reserved ", OTHER))).isInstanceOf(SQLException.class);
        assertThat(number("SELECT COUNT(*) FROM global_name_reservations")).isEqualTo(1);
        assertThat(number("SELECT SUM(claim_count) FROM global_name_reservations"))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"ACCOUNT,FIRM", "ACCOUNT,PLAYER", "FIRM,ACCOUNT", "FIRM,PLAYER", "PLAYER,ACCOUNT", "PLAYER,FIRM"})
    void failedRenameRetainsBothOriginalClaims(String first, String second) throws Exception {
        sql(create(first, "Occupied", OWNER));
        sql(create(second, "Original", OTHER));
        assertThatThrownBy(() -> sql(rename(second, "Occupied"))).isInstanceOf(SQLException.class);
        assertThat(number("SELECT COUNT(*) FROM global_name_reservations WHERE name_key IN ('Occupied','Original')"))
                .isEqualTo(2);
    }

    @Test
    void personalProjectionSharesOnlyItsOwnPlayerAndRetainsOtherProjectionOnRename() throws Exception {
        sql("INSERT INTO accounts(account_type,owner_uuid_bin,display_name) VALUES ('PERSONAL',UNHEX('" + OWNER
                + "'),'Alice')");
        sql(create("PLAYER", "ALICE", OWNER));
        assertThat(number("SELECT claim_count FROM global_name_reservations WHERE name_key='Alice'"))
                .isEqualTo(2);
        sql("UPDATE accounts SET display_name='Wallet' WHERE account_type='PERSONAL'");
        assertThat(number("SELECT claim_count FROM global_name_reservations WHERE name_key='Alice'"))
                .isEqualTo(1);
        assertThatThrownBy(() -> sql(create("FIRM", "alice", OWNER))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> sql("UPDATE accounts SET owner_uuid_bin=UNHEX('" + OTHER + "'),display_name='Alice'"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void accentAndSpaceVariantsConflictWhilePunctuationAndFloodgateDotsRemainSignificant() throws Exception {
        sql(create("ACCOUNT", "Café", OWNER));
        assertThatThrownBy(() -> sql(create("FIRM", " cafe ", OTHER))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> sql(create("FIRM", "Cafe\u0301", OTHER))).isInstanceOf(SQLException.class);
        sql(create("ACCOUNT", "A-B", OWNER));
        sql(create("FIRM", "AB", OTHER));
        sql(create("PLAYER", ".Bedrock", OWNER));
        sql(create("FIRM", "Bedrock", OTHER));
        assertThat(number("SELECT COUNT(*) FROM global_name_reservations")).isEqualTo(5);
    }

    @Test
    void archivedAccountsKeepClaimsAndDisbandedFirmsNeedExplicitRelease() throws Exception {
        sql(create("ACCOUNT", "Archive", OWNER));
        sql("UPDATE accounts SET is_archived=1 WHERE display_name='Archive'");
        assertThatThrownBy(() -> sql(create("FIRM", "Archive", OTHER))).isInstanceOf(SQLException.class);
        sql(create("FIRM", "FirmName", OWNER));
        sql("UPDATE firm SET is_archived=1 WHERE display_name='FirmName'");
        assertThatThrownBy(() -> sql(create("ACCOUNT", "FirmName", OTHER))).isInstanceOf(SQLException.class);
        sql("UPDATE firm SET name_released=1 WHERE display_name='FirmName'");
        sql(create("ACCOUNT", "FirmName", OTHER));
        assertThatThrownBy(() -> sql("UPDATE firm SET is_archived=0 WHERE display_name='FirmName'"))
                .isInstanceOf(SQLException.class);
        assertThat(number("SELECT is_archived FROM firm WHERE display_name='FirmName'"))
                .isEqualTo(1);
    }

    @Test
    void aliasesShareTheirAccountAndRetainHistoricalSignOwnership() throws Exception {
        sql(create("ACCOUNT", "FirstAlias", OWNER));
        sql(create("FIRM", "Shop", OWNER));
        sql(
                "INSERT INTO firm_accounts(firm_id,account_id,shop_alias) SELECT f.firm_id,a.account_id,'FirstAlias' FROM firm f CROSS JOIN accounts a");
        sql("UPDATE firm_accounts SET shop_alias='NextAlias'");
        assertThatThrownBy(() -> sql(create("FIRM", "FirstAlias", OTHER))).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> sql(create("PLAYER", "NextAlias", OTHER))).isInstanceOf(SQLException.class);
        assertThat(number("SELECT COUNT(*) FROM firm_shop_alias_reservations")).isEqualTo(2);
        sql("UPDATE firm_accounts SET shop_alias=NULL,removed_at=CURRENT_TIMESTAMP");
        assertThatThrownBy(() -> sql(create("FIRM", "NextAlias", OTHER))).isInstanceOf(SQLException.class);
    }

    @Test
    void conflictingLoginUpsertCannotUpdateTheOtherPlayersIdentityOrEpoch() throws Exception {
        sql(create("PLAYER", "Steve", OWNER));
        assertThatThrownBy(() -> sql(create("PLAYER", "steve", OTHER)
                        + " ON DUPLICATE KEY UPDATE current_name=VALUES(current_name),last_login_epoch=9999"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("GLOBAL_NAME_CONFLICT");
        assertThat(number("SELECT last_login_epoch FROM economy_players WHERE player_uuid_bin=UNHEX('" + OWNER + "')"))
                .isEqualTo(1000);
        assertThat(number("SELECT COUNT(*) FROM economy_players")).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void separateConnectionsArbitrateCreateAndRenameRaces(boolean rename) throws Exception {
        if (rename) sql(create("FIRM", "BeforeRace", OWNER));
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> race(create("ACCOUNT", "Race", OTHER), ready, go));
            var second =
                    pool.submit(() -> race(rename ? rename("FIRM", "Race") : create("FIRM", "Race", OWNER), ready, go));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS))
                    .isEqualTo(1);
        }
        assertThat(number("SELECT claim_count FROM global_name_reservations WHERE name_key='Race'"))
                .isEqualTo(1);
        assertThat(
                        number(
                                "SELECT COUNT(*) FROM global_name_claims c LEFT JOIN global_name_reservations r USING(name_key) WHERE r.name_key IS NULL"))
                .isZero();
    }

    @Test
    void preflightRejectsExistingCollisionsWithoutRewritingSourceRows() throws Exception {
        String source;
        try (var in = getClass().getClassLoader().getResourceAsStream("db/migration/V38__global_name_namespace.sql")) {
            source = new String(
                    java.util.Objects.requireNonNull(in).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String guard = source.substring(
                source.indexOf("CREATE PROCEDURE global_names_preflight()"),
                source.indexOf("CALL global_names_preflight()"));
        String[] tables = {"accounts", "firm", "economy_players", "firm_accounts", "firm_shop_alias_reservations"};
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            try {
                // Session-local pre-migration fixtures exercise the guard logic;
                // test the actual guard without disabling production constraints.
                for (String table : tables) {
                    st.execute("CREATE TEMPORARY TABLE ns_" + table + " LIKE " + table);
                    guard = guard.replaceAll("\\b" + table + "\\b", "ns_" + table);
                }
                st.execute(
                        "INSERT INTO ns_accounts(account_type,owner_uuid_bin,display_name) VALUES ('PERSONAL',UNHEX('"
                                + OWNER + "'),'Alice')");
                st.execute(create("PLAYER", "alice", OWNER).replace("INTO economy_players", "INTO ns_economy_players"));
                st.execute(guard);
                st.execute("CALL global_names_preflight()"); // Same identity projection backfills safely.
                st.execute(create("FIRM", "Alice", OTHER).replace("INTO firm", "INTO ns_firm"));
                assertThatThrownBy(() -> st.execute("CALL global_names_preflight()"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("unresolved global name collisions");
                try (var rs = st.executeQuery("SELECT display_name FROM ns_firm")) {
                    rs.next();
                    assertThat(rs.getString(1)).isEqualTo("Alice");
                }
                st.execute("DELETE FROM ns_firm");
                st.execute("UPDATE ns_accounts SET display_name=' Alice '");
                assertThatThrownBy(() -> st.execute("CALL global_names_preflight()"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("surrounding whitespace");
            } finally {
                st.execute("DROP PROCEDURE IF EXISTS global_names_preflight");
                for (String table : tables) st.execute("DROP TEMPORARY TABLE IF EXISTS ns_" + table);
            }
        }
    }

    @Test
    void aTransactionRollbackRemovesNewClaimsAlongWithSourceRows() throws Exception {
        sql(create("FIRM", "Taken", OWNER));
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                st.execute(create("ACCOUNT", "Temporary", OTHER));
                assertThatThrownBy(() -> st.execute(create("ACCOUNT", "Taken", OTHER)))
                        .isInstanceOf(SQLException.class);
            } finally {
                c.rollback();
            }
        }
        assertThat(number("SELECT COUNT(*) FROM accounts")).isZero();
        assertThat(number("SELECT COUNT(*) FROM global_name_reservations WHERE name_key='Temporary'"))
                .isZero();
        assertThat(number("SELECT COUNT(*) FROM global_name_claims")).isEqualTo(1);
    }

    @Test
    void firmTransactionDoesNotBlockTreasurysIndependentDefaultAccountTransaction() throws Exception {
        try (Connection outer = dataSource.getConnection();
                Statement firm = outer.createStatement()) {
            outer.setAutoCommit(false);
            try {
                firm.execute(create("FIRM", "Nested", OWNER));
                // Business calls Treasury through an API backed by another
                // transaction before committing its firm. Missing-row gap locks
                // in the registry must not make that call wait on its caller.
                try (Connection inner = dataSource.getConnection();
                        Statement account = inner.createStatement()) {
                    account.execute("SET innodb_lock_wait_timeout=2");
                    account.execute(create("ACCOUNT", "Nested Corporate Account", OWNER));
                }
                outer.commit();
            } finally {
                outer.rollback();
            }
        }
        assertThat(number("SELECT COUNT(*) FROM global_name_reservations")).isEqualTo(2);
        assertThat(number("SELECT COUNT(*) FROM global_name_claims WHERE name_key IS NULL OR entity_key IS NULL"))
                .isZero();
    }

    private int race(String query, CountDownLatch ready, CountDownLatch go) throws Exception {
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            ready.countDown();
            if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("race did not start");
            try {
                st.executeUpdate(query);
                return 1;
            } catch (SQLException e) {
                if (!e.getMessage().contains("GLOBAL_NAME_CONFLICT") && e.getErrorCode() != 1213) throw e;
                return 0; // InnoDB may abort a deadlock loser; neither writer may leave partial state.
            }
        }
    }

    private static String create(String kind, String name, String owner) {
        return switch (kind) {
            case "ACCOUNT" ->
                "INSERT INTO accounts(account_type,owner_uuid_bin,display_name) VALUES ('BUSINESS',UNHEX('" + owner
                        + "'),'" + name + "')";
            case "FIRM" ->
                "INSERT INTO firm(display_name,proprietor_uuid_bin) VALUES ('" + name + "',UNHEX('" + owner + "'))";
            case "PLAYER" ->
                "INSERT INTO economy_players(player_uuid_bin,current_name,last_login_epoch) VALUES (UNHEX('" + owner
                        + "'),'" + name + "',1000)";
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static String rename(String kind, String name) {
        return switch (kind) {
            case "ACCOUNT" -> "UPDATE accounts SET display_name='" + name + "'";
            case "FIRM" -> "UPDATE firm SET display_name='" + name + "'";
            case "PLAYER" -> "UPDATE economy_players SET current_name='" + name + "'";
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private void sql(String query) throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            st.executeUpdate(query);
        }
    }

    private long number(String query) throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement();
                var rs = st.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
