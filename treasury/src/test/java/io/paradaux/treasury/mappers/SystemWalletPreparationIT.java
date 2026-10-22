package io.paradaux.treasury.mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paradaux.hibernia.testsupport.db.SkipLedgerInvariants;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

@SkipLedgerInvariants("Independent schema fixtures exercise migration ordering, not ledger writes")
class SystemWalletPreparationIT extends IntegrationTestBase {
    private static final String MIGRATION = "db/migration/V42__stable_system_plugin_key.sql";

    @Test
    void preV40CaptureSurvivesEveryPartialDdlRerunRenameAndNormalMigration() throws Exception {
        String schema = "wallet_prep_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            String originalCatalog = connection.getCatalog();
            statement.execute("CREATE DATABASE " + schema);
            try {
                Flyway flyway = Flyway.configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                        .target("39")
                        .load();
                flyway.migrate();
                connection.setCatalog(schema);
                statement.execute("INSERT INTO accounts(account_id,account_type,owner_uuid_bin,display_name) "
                        + "VALUES (901,'SYSTEM',UNHEX(REPEAT('11',16)),'FixturePlugin')");
                statement.execute("INSERT INTO account_balances_mat(account_id,balance) VALUES (901,123.45)");
                statement.execute("INSERT INTO economy_players(player_uuid_bin,current_name) "
                        + "VALUES (UNHEX(REPEAT('22',16)),'FixturePlugin')");
                assertThatThrownBy(() -> Flyway.configure()
                                .dataSource(dataSource)
                                .schemas(schema)
                                .defaultSchema(schema)
                                .locations("classpath:db/migration")
                                .target("40")
                                .load()
                                .migrate())
                        .hasMessageContaining("unresolved global name collisions");
                // Reproduce the failure shape: V40 history failed, guard only.
                assertThat(number(
                                connection,
                                "SELECT COUNT(*) FROM flyway_schema_history WHERE version='40' AND success=0"))
                        .isEqualTo(1);
                assertThat(
                                number(
                                        connection,
                                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='global_name_claims'"))
                        .isZero();
                List<String> steps = preparationSteps();
                // Reject malformed preparations rather than silently choosing keys.
                statement.execute(steps.getFirst());
                statement.execute("UPDATE accounts SET system_plugin_key=' ' WHERE account_id=901");
                assertThatThrownBy(() -> executeSteps(connection, steps)).hasMessageContaining("must be nonblank");
                statement.execute("UPDATE accounts SET system_plugin_key=NULL WHERE account_id=901");
                statement.execute(
                        "INSERT INTO accounts(account_id,account_type,owner_uuid_bin,display_name,system_plugin_key) "
                                + "VALUES (902,'SYSTEM',UNHEX(REPEAT('33',16)),'Distinct label','fixtureplugin')");
                assertThatThrownBy(() -> executeSteps(connection, steps))
                        .hasMessageContaining("must be nonblank and unique");
                statement.execute("DELETE FROM accounts WHERE account_id=902");
                statement.execute(
                        "INSERT INTO accounts(account_id,account_type,owner_uuid_bin,display_name,system_plugin_key) "
                                + "VALUES (903,'BUSINESS',UNHEX(REPEAT('44',16)),'Business label','NotAllowed')");
                assertThatThrownBy(() -> executeSteps(connection, steps))
                        .hasMessageContaining("non-SYSTEM keys must be NULL");
                statement.execute("DELETE FROM accounts WHERE account_id=903");
                for (int prefix = 1; prefix <= steps.size(); prefix++) {
                    // Simulate termination after each top-level statement (DDL
                    // implicitly committed), then replay from the start.
                    // Restore the ORIGINAL pre-preparation schema for each prefix;
                    // otherwise later cases would only interrupt an already-complete replay.
                    statement.execute("DROP TRIGGER IF EXISTS system_plugin_key_insert");
                    statement.execute("DROP TRIGGER IF EXISTS system_plugin_key_update");
                    statement.execute(
                            "ALTER TABLE accounts DROP INDEX uq_one_system_per_plugin, "
                                    + "MODIFY COLUMN _system_plugin VARCHAR(255) AS (CASE WHEN account_type='SYSTEM' THEN display_name END) VIRTUAL, "
                                    + "DROP COLUMN system_plugin_key, ADD UNIQUE KEY uq_one_system_per_plugin (_system_plugin), ALGORITHM=COPY");
                    for (String step : steps.subList(0, prefix)) statement.execute(step);
                    assertThat(
                                    number(
                                            connection,
                                            "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() "
                                                    + "AND table_name='accounts' AND index_name='uq_one_system_per_plugin' AND non_unique=0"))
                            .isEqualTo(1);
                    for (String step : steps) statement.execute(step);
                    assertWallet(connection, "FixturePlugin");
                }
                executeSteps(connection, wrapperSteps());
                statement.execute("UPDATE accounts SET display_name='Temporary wallet label' WHERE account_id=901");
                for (String step : steps) statement.execute(step);
                assertWallet(connection, "Temporary wallet label");
                executeSteps(connection, wrapperSteps());
                // Repair only this scratch DB's known failed entry; successful
                // V38/V39 checksums remain governed by the unmodified scripts.
                Flyway latest = Flyway.configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .defaultSchema(schema)
                        .locations("classpath:db/migration")
                        .load();
                latest.repair();
                latest.migrate();
                latest.validate();
                assertWallet(connection, "Temporary wallet label");
                executeSteps(connection, wrapperSteps());
                assertThat(
                                number(
                                        connection,
                                        "SELECT COUNT(*) FROM global_name_claims WHERE source_kind='ACCOUNT' AND subject_id='901' AND name_key='Temporary wallet label'"))
                        .isEqualTo(1);
                assertThat(
                                number(
                                        connection,
                                        "SELECT COUNT(*) FROM global_name_reservations WHERE name_key='FixturePlugin' AND entity_key LIKE 'P:%'"))
                        .isEqualTo(1);
                assertThatThrownBy(() -> statement.execute(
                                "UPDATE accounts SET display_name='FixturePlugin' WHERE account_id=901"))
                        .isInstanceOf(SQLException.class);
            } finally {
                connection.setCatalog(originalCatalog);
                statement.execute("DROP DATABASE " + schema);
            }
        }
    }

    private static void assertWallet(Connection connection, String label) throws Exception {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT a.account_id,HEX(a.owner_uuid_bin),a.display_name,a.system_plugin_key,b.balance FROM accounts a JOIN account_balances_mat b USING(account_id) WHERE a.account_id=901")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(901);
            assertThat(rows.getString(2)).isEqualTo("11".repeat(16));
            assertThat(rows.getString(3)).isEqualTo(label);
            assertThat(rows.getString(4)).isEqualTo("FixturePlugin");
            assertThat(rows.getBigDecimal(5)).isEqualByComparingTo("123.45");
            assertThat(rows.next()).isFalse();
        }
    }

    private static int number(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static List<String> preparationSteps() throws Exception {
        try (var input = SystemWalletPreparationIT.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            return sqlSteps(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static List<String> wrapperSteps() throws Exception {
        Path wrapper = Path.of("ops/system-wallet-routing/prepare-v42.sql");
        if (!Files.exists(wrapper)) wrapper = Path.of("../ops/system-wallet-routing/prepare-v42.sql");
        return sqlSteps(Files.readString(wrapper, StandardCharsets.UTF_8));
    }

    // Execute the same top-level statements as the MariaDB CLI, including its
    // delimiter and SOURCE directives; no JDBC splitting inside procedure bodies.
    private static List<String> sqlSteps(String script) throws Exception {
        List<String> statements = new ArrayList<>();
        String delimiter = ";";
        StringBuilder pending = new StringBuilder();
        for (String line : script.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
            if (trimmed.startsWith("DELIMITER ")) {
                delimiter = trimmed.substring("DELIMITER ".length());
            } else if (trimmed.startsWith("SOURCE ")) {
                assertThat(pending.toString()).isBlank();
                statements.addAll(preparationSteps());
            } else {
                pending.append(line).append('\n');
                if (trimmed.endsWith(delimiter)) {
                    String sql = pending.toString().stripTrailing();
                    statements.add(sql.substring(0, sql.length() - delimiter.length()));
                    pending.setLength(0);
                }
            }
        }
        assertThat(pending.toString()).isBlank();
        return statements;
    }

    private static void executeSteps(Connection connection, List<String> steps) throws Exception {
        try (var statement = connection.createStatement()) {
            for (String step : steps) statement.execute(step);
        }
    }
}
