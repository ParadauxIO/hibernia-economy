package io.paradaux.treasuryrestapi.testsupport;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;

/**
 * One MariaDB for the whole JVM, used by the DB-backed integration tests, with the
 * <b>real</b> economy schema.
 *
 * <ol>
 *   <li><b>External (CI, and the recommended local path)</b>: when
 *       {@code TREASURY_REST_TEST_JDBC_URL} (+ {@code TREASURY_REST_TEST_DB_USER} /
 *       {@code TREASURY_REST_TEST_DB_PASS}) is set, that database is used as-is.</li>
 *   <li><b>Embedded (fallback)</b>: otherwise MariaDB4j boots a throwaway server.
 *       Its bundled binary needs system libs newer distros/runners don't ship, which
 *       is why CI uses the external path.</li>
 * </ol>
 *
 * <p>In both modes the schema is built by running the authoritative
 * <b>economy-flyway</b> migrations with Flyway (staged onto the test classpath at
 * {@code db/migration} by the build) — the same approach as the Treasury (PAR-239)
 * and Business (PAR-242) harnesses. Tests and production therefore share one source
 * of schema truth: every FK, NOT NULL column, view, UUID helper function and the
 * {@code trg_postings_ai/au/ad} triggers that are the sole writer of
 * {@code account_balances_mat}. This replaced a hand-maintained 19-table stand-in
 * that had no FKs and no triggers, so tests could seed rows production would reject
 * and never saw trigger-materialised balances.
 *
 * <p>Migration runs once per JVM (inside {@link #ensureStarted()}), not per test
 * class: Spring contexts are cached and every {@code @DynamicPropertySource} funnels
 * through here. Re-running against an already-migrated external DB (a second forked
 * test JVM, or a re-run against the same scratch DB) is a Flyway no-op.
 *
 * <p>Isolation: {@link #truncateAll()} empties every base table except
 * {@code flyway_schema_history}, with the list read from {@code information_schema}
 * so a new migration's table is covered without touching this class.
 */
public final class EmbeddedMariaDb {

    private static final String DB_NAME = "treasury_rest_test";
    private static final String HISTORY_TABLE = "flyway_schema_history";

    private static volatile String jdbcUrl;
    private static String username = "root";
    private static String password = "";
    private static DB db;
    /** Base tables to truncate between tests; resolved once after migration. */
    private static List<String> dataTables;

    private EmbeddedMariaDb() {}

    public static synchronized void ensureStarted() {
        if (jdbcUrl != null) return;
        try {
            // CI points us at a real MariaDB service: MariaDB4j's bundled server
            // binary needs system libs the GitHub runner image doesn't ship, so
            // when TREASURY_REST_TEST_JDBC_URL is set we use that DB and skip the
            // embedded server (see .github/workflows/treasury-rest-api-test.yml).
            String envUrl = System.getenv("TREASURY_REST_TEST_JDBC_URL");
            String url;
            if (envUrl != null && !envUrl.isBlank()) {
                username = envOrDefault("TREASURY_REST_TEST_DB_USER", "root");
                password = envOrDefault("TREASURY_REST_TEST_DB_PASS", "");
                url = envUrl;
            } else {
                DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
                // Separate JVMs run these suites concurrently. MariaDB4j removes its
                // temporary binaries at shutdown, so none of its directories may be shared.
                var directory = java.nio.file.Files.createTempDirectory("economy-test-db-");
                cfg.setBaseDir(directory.resolve("base").toFile());
                cfg.setDataDir(directory.resolve("data").toFile());
                cfg.setTmpDir(directory.resolve("tmp").toString());
                cfg.setPort(0); // free port
                db = DB.newEmbeddedDB(cfg.build());
                db.start();
                db.createDB(DB_NAME);
                url = cfg.getURL(DB_NAME);
                username = "root";
                password = "";
            }
            migrate(url);
            dataTables = listDataTables(url);
            // Publish last: jdbcUrl != null is the "ready" flag the accessors check.
            jdbcUrl = url;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start/migrate the test MariaDB", e);
        }
    }

    private static String envOrDefault(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : def;
    }

    public static String jdbcUrl() {
        ensureStarted();
        return jdbcUrl;
    }

    public static String username() {
        ensureStarted();
        return username;
    }

    public static String password() {
        ensureStarted();
        return password;
    }

    public static Connection connect() throws Exception {
        ensureStarted();
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    /**
     * Empty every base table (all but {@code flyway_schema_history}) for isolation,
     * resetting AUTO_INCREMENT counters, then restore the one row the app treats as
     * always present. Migration-seeded reference rows (explorer roles, feature
     * flags) are wiped too: nothing in this module reads them, and tests that need a
     * row seed it explicitly.
     */
    public static void truncateAll() {
        try (Connection c = connect();
                Statement st = c.createStatement()) {
            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String t : dataTables) {
                st.execute("TRUNCATE TABLE `" + t + "`");
            }
            // Re-seed the single-row dispatcher watermark (V13 inserts it; the
            // dispatcher's ingest() reads/advances it and assumes it exists).
            st.execute("INSERT INTO webhook_cursor (id, last_dispatched_txn_id) VALUES (1, 0)");
            st.execute("SET FOREIGN_KEY_CHECKS = 1");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** UUID -> BINARY(16) bytes, matching the production UuidTypeHandler. */
    public static byte[] uuidBytes(UUID u) {
        if (u == null) return null;
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }

    private static void migrate(String url) {
        Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private static List<String> listDataTables(String url) throws Exception {
        List<String> tables = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, username, password);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' "
                        + "AND table_name <> '" + HISTORY_TABLE + "' ORDER BY table_name")) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        if (tables.isEmpty()) {
            throw new IllegalStateException("economy-flyway migrations produced no tables — "
                    + "are they staged onto the test classpath at db/migration?");
        }
        return List.copyOf(tables);
    }
}
