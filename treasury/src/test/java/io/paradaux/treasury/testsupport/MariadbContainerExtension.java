package io.paradaux.treasury.testsupport;

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
 * Two-mode MariaDB harness:
 *
 * <ol>
 *   <li><b>Embedded (default — local dev)</b>: boots MariaDB4j once per JVM and
 *       exposes a Hikari {@link DataSource}. No Docker, no system MariaDB needed.</li>
 *   <li><b>External (CI)</b>: when {@code TREASURY_TEST_JDBC_URL} is set, point
 *       Hikari at that URL instead (the workflow boots a MariaDB service
 *       container). MariaDB4j is skipped — its bundled binary needs system libs
 *       newer Ubuntu runners don't ship.</li>
 * </ol>
 *
 * <p>In both modes the schema is built by running the authoritative
 * <b>economy-flyway</b> migrations with Flyway (staged onto the test classpath at
 * {@code db/migration} by the build). Tests and production therefore share one
 * source of schema truth — triggers included — and there is no bundled
 * {@code schema.sql} snapshot to drift.
 *
 * <p>Each test method gets a freshly truncated set of tables via
 * {@link #truncateAll(DataSource)} called from the integration test base.
 */
public final class MariadbContainerExtension {

    private static final String DATABASE_NAME = "treasury_test";

    /** When set, the harness uses this URL instead of booting an embedded DB. */
    private static final String EXTERNAL_URL_ENV  = "TREASURY_TEST_JDBC_URL";
    private static final String EXTERNAL_USER_ENV = "TREASURY_TEST_DB_USER";
    private static final String EXTERNAL_PASS_ENV = "TREASURY_TEST_DB_PASS";

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

    /**
     * Applies the economy-flyway migrations to the database. Runs once per JVM
     * (inside the cached-DataSource computation) and yields the full schema the
     * production database has, including the {@code account_balances_mat}
     * triggers that {@code LedgerService.transfer} relies on.
     */
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
        cfg.setMaximumPoolSize(4);
        cfg.setPoolName("treasury-tests-external");
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
        cfg.setMaximumPoolSize(4);
        cfg.setPoolName("treasury-tests");
        return new HikariDataSource(cfg);
    }

    /** Holder keeps the embedded DB alive until JUnit closes the root context. */
    private static final class DbHolder implements ExtensionContext.Store.CloseableResource {
        final DB db;
        final String url;
        DbHolder(DB db, String url) { this.db = db; this.url = url; }
        @Override public void close() throws Exception { db.stop(); }
    }

    /** Truncates all data tables. Foreign-key checks disabled for the duration. */
    public static void truncateAll(DataSource dataSource) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            st.execute("TRUNCATE TABLE economy_players");
            st.execute("TRUNCATE TABLE government_fines");
            st.execute("TRUNCATE TABLE account_redirects");
            st.execute("TRUNCATE TABLE account_group_access");
            st.execute("TRUNCATE TABLE account_access");
            st.execute("TRUNCATE TABLE account_balances_mat");
            st.execute("TRUNCATE TABLE ledger_postings");
            st.execute("TRUNCATE TABLE ledger_txns");
            st.execute("TRUNCATE TABLE accounts");
            st.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }
}
