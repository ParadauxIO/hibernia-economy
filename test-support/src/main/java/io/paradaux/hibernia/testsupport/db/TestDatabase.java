package io.paradaux.hibernia.testsupport.db;

import java.util.Locale;
import java.util.Objects;

/**
 * Identifies one module's integration-test database: the schema name the embedded
 * MariaDB4j instance creates, and the environment-variable prefix that switches
 * the harness to an external server.
 *
 * <p>For prefix {@code TREASURY} the harness reads {@code TREASURY_TEST_JDBC_URL},
 * {@code TREASURY_TEST_DB_USER} and {@code TREASURY_TEST_DB_PASS} — the names the
 * CI workflows already export, so moving a module onto this shared harness needs no
 * workflow change. When the URL variable is unset or blank the harness boots
 * MariaDB4j instead (local dev: no Docker, no system MariaDB).
 *
 * @param databaseName schema name used in embedded mode (e.g. {@code treasury_test})
 * @param envPrefix    upper-case prefix of the external-DB variables (e.g. {@code TREASURY})
 */
public record TestDatabase(String databaseName, String envPrefix) {

    public TestDatabase {
        Objects.requireNonNull(databaseName, "databaseName");
        Objects.requireNonNull(envPrefix, "envPrefix");
        envPrefix = envPrefix.toUpperCase(Locale.ROOT);
    }

    public static TestDatabase of(String databaseName, String envPrefix) {
        return new TestDatabase(databaseName, envPrefix);
    }

    public String urlEnv() {
        return envPrefix + "_TEST_JDBC_URL";
    }

    public String userEnv() {
        return envPrefix + "_TEST_DB_USER";
    }

    public String passEnv() {
        return envPrefix + "_TEST_DB_PASS";
    }
}
