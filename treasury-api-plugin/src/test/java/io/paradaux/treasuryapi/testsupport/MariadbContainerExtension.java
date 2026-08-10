package io.paradaux.treasuryapi.testsupport;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfiguration;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * Two-mode MariaDB harness for the treasury-api-plugin mapper ITs, mirroring the
 * sibling Treasury/Business projects.
 *
 * <ol>
 *   <li><b>Embedded (default — local dev)</b>: boots MariaDB4j once per JVM and
 *       exposes a Hikari {@link DataSource}. No Docker, no system MariaDB needed.</li>
 *   <li><b>External (CI)</b>: when {@code TREASURYAPI_TEST_JDBC_URL} is set, point
 *       Hikari at that URL instead. MariaDB4j is skipped — its bundled binary needs
 *       system libs newer runners don't ship.</li>
 * </ol>
 *
 * <p>In both modes the schema is built by running the authoritative
 * <b>economy-flyway</b> migrations with Flyway (staged onto the test classpath at
 * {@code db/migration} by the build), so tests and production share one source of
 * schema truth — the {@code api_keys}, {@code explorer_*} and {@code firm_*} tables
 * and the UUID helper functions all come from there.
 */
public final class MariadbContainerExtension {

    private static final String DATABASE_NAME = "treasuryapi_test";

    private static final String EXTERNAL_URL_ENV  = "TREASURYAPI_TEST_JDBC_URL";
    private static final String EXTERNAL_USER_ENV = "TREASURYAPI_TEST_DB_USER";
    private static final String EXTERNAL_PASS_ENV = "TREASURYAPI_TEST_DB_PASS";

    private static final Namespace NAMESPACE = Namespace.create(MariadbContainerExtension.class);
    private static final String DB_KEY = "embedded-db";
    private static final String DATASOURCE_KEY = "datasource";

    private MariadbContainerExtension() {}

    public static synchronized DataSource dataSource(ExtensionContext context) {
        Store store = context.getRoot().getStore(NAMESPACE);

        String externalUrl = System.getenv(EXTERNAL_URL_ENV);
        if (externalUrl != null && !externalUrl.isBlank()) {
            return store.getOrComputeIfAbsent(
                    DATASOURCE_KEY,
                    k -> migrated(buildExternalDataSource(externalUrl)),
                    HikariDataSource.class);
        }

        DbHolder holder = store.getOrComputeIfAbsent(
                DB_KEY, k -> startEmbeddedDb(), DbHolder.class);
        return store.getOrComputeIfAbsent(
                DATASOURCE_KEY, k -> migrated(buildDataSource(holder.url)), HikariDataSource.class);
    }

    private static HikariDataSource migrated(HikariDataSource dataSource) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return dataSource;
    }

    private static HikariDataSource buildExternalDataSource(String jdbcUrl) {
        String user = System.getenv(EXTERNAL_USER_ENV);
        String pass = System.getenv(EXTERNAL_PASS_ENV);
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(user != null ? user : "root");
        cfg.setPassword(pass != null ? pass : "");
        cfg.setMaximumPoolSize(8);
        cfg.setPoolName("treasuryapi-tests-external");
        return new HikariDataSource(cfg);
    }

    private static DbHolder startEmbeddedDb() {
        try {
            DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
            cfg.setPort(0); // dynamic free port
            DBConfiguration dbConfig = cfg.build();
            DB db = DB.newEmbeddedDB(dbConfig);
            db.start();
            db.createDB(DATABASE_NAME);

            String url = cfg.getURL(DATABASE_NAME);
            return new DbHolder(db, url);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start embedded MariaDB", e);
        }
    }

    private static HikariDataSource buildDataSource(String jdbcUrl) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername("root");
        cfg.setPassword("");
        cfg.setMaximumPoolSize(8);
        cfg.setPoolName("treasuryapi-tests");
        return new HikariDataSource(cfg);
    }

    /** Holder keeps the embedded DB alive until JUnit closes the root context. */
    private static final class DbHolder implements ExtensionContext.Store.CloseableResource {
        final DB db;
        final String url;
        DbHolder(DB db, String url) { this.db = db; this.url = url; }
        @Override public void close() throws Exception { db.stop(); }
    }

    /** Truncates the tables the treasury-api-plugin mappers touch, plus their FK parents. */
    public static void truncateAll(DataSource dataSource) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            st.execute("TRUNCATE TABLE api_keys");
            st.execute("TRUNCATE TABLE explorer_group_member");
            st.execute("TRUNCATE TABLE explorer_group");
            st.execute("TRUNCATE TABLE explorer_role");
            st.execute("TRUNCATE TABLE explorer_link_code");
            st.execute("TRUNCATE TABLE explorer_identity");
            st.execute("TRUNCATE TABLE firm_employee");
            st.execute("TRUNCATE TABLE firm_role");
            st.execute("TRUNCATE TABLE firm");
            st.execute("TRUNCATE TABLE accounts");
            st.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }
}
