package io.paradaux.treasury.startup;

import static org.assertj.core.api.Assertions.*;

import io.paradaux.treasury.testsupport.IntegrationTestBase;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

class SchemaMigratorIT extends IntegrationTestBase {
    private FluentConfiguration configuration() throws Exception {
        try (var connection = dataSource.getConnection()) {
            return SchemaMigrator.configure(SchemaMigratorTest.database())
                    .dataSource(dataSource)
                    .schemas(connection.getCatalog());
        }
    }

    @Test
    void existingHistoryIsValidatedAndRestartIsANoOp() throws Exception {
        var flyway = configuration().load();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void shippedRuntimeMigratesAgainstMariaDb() throws Exception {
        var artifact = java.nio.file.Path.of(System.getProperty("treasury.pluginJar"));
        try (var loader = new java.net.URLClassLoader(
                        new java.net.URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
                var connection = dataSource.getConnection()) {
            var flywayType = loader.loadClass("org.flywaydb.core.Flyway");
            var configuration =
                    flywayType.getMethod("configure", ClassLoader.class).invoke(null, loader);
            var type = configuration.getClass();
            type.getMethod("dataSource", javax.sql.DataSource.class).invoke(configuration, dataSource);
            type.getMethod("schemas", String[].class)
                    .invoke(configuration, (Object) new String[] {connection.getCatalog()});
            var flyway = type.getMethod("load").invoke(configuration);
            var result = flywayType.getMethod("migrate").invoke(flyway);
            assertThat(result.getClass().getField("migrationsExecuted").getInt(result))
                    .isZero();
        }
    }

    @Test
    void checksumMismatchStopsMigrationWithoutRepairingHistory() throws Exception {
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            int checksum;
            try (var result = statement.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version='1'")) {
                assertThat(result.next()).isTrue();
                checksum = result.getInt(1);
            }
            try {
                statement.executeUpdate("UPDATE flyway_schema_history SET checksum=0 WHERE version='1'");
                connection.commit();
                var flyway = configuration().load();
                assertThatThrownBy(flyway::migrate).hasMessageContaining("checksum mismatch");
                try (var result =
                        statement.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version='1'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isZero();
                }
            } finally {
                statement.executeUpdate("UPDATE flyway_schema_history SET checksum=" + checksum + " WHERE version='1'");
                connection.commit();
            }
        }
    }
}
