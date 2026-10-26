package io.paradaux.hibernia.testsupport.db;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfiguration;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.jetbrains.annotations.Nullable;

/**
 * One JVM-wide migrated test database: the (optional) embedded MariaDB4j server,
 * the pooled {@link DataSource}, the table inventory and the migration-seeded rows.
 * Created once per {@link TestDatabase} and cached in JUnit's root store, which
 * closes it (pool first, then the embedded server) when the test plan finishes.
 */
final class DatabaseHandle implements AutoCloseable {

    /**
     * Hikari pool size. It must exceed the widest thread fan-out any concurrency IT
     * uses (24 threads, plus the harness's own reset/invariant connections), otherwise
     * workers queue on the pool instead of racing in the database and a "concurrent"
     * test silently runs serially — which is how the old 4-connection pool let lost
     * updates go unnoticed.
     */
    static final int POOL_SIZE = 32;

    private static final String HISTORY_TABLE = "flyway_schema_history";

    private final @Nullable DB embedded;
    private final HikariDataSource dataSource;
    /** Every base table except Flyway's history, in information_schema order. */
    private final List<String> tables;
    /** Rows the migrations themselves insert (role definitions, webhook cursor…). */
    private final Map<String, SeedTable> seeds;

    private DatabaseHandle(
            @Nullable DB embedded, HikariDataSource dataSource, List<String> tables, Map<String, SeedTable> seeds) {
        this.embedded = embedded;
        this.dataSource = dataSource;
        this.tables = tables;
        this.seeds = seeds;
    }

    DataSource dataSource() {
        return dataSource;
    }

    static DatabaseHandle open(TestDatabase spec) {
        String externalUrl = System.getenv(spec.urlEnv());
        DB embedded = null;
        HikariDataSource ds;
        if (externalUrl != null && !externalUrl.isBlank()) {
            // External (CI): the workflow boots a MariaDB service container. MariaDB4j
            // is skipped — its bundled binary needs system libs newer runners don't ship.
            String user = System.getenv(spec.userEnv());
            String pass = System.getenv(spec.passEnv());
            ds = pool(
                    externalUrl,
                    user != null ? user : "root",
                    pass != null ? pass : "",
                    spec.databaseName() + "-external");
        } else {
            try {
                DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
                // Separate JVMs run these suites concurrently. MariaDB4j removes its
                // temporary binaries at shutdown, so none of its directories may be shared.
                var directory = java.nio.file.Files.createTempDirectory("economy-test-db-");
                cfg.setBaseDir(directory.resolve("base").toFile());
                cfg.setDataDir(directory.resolve("data").toFile());
                cfg.setTmpDir(directory.resolve("tmp").toString());
                cfg.setPort(0); // dynamic free port
                DBConfiguration dbConfig = cfg.build();
                embedded = DB.newEmbeddedDB(dbConfig);
                embedded.start();
                embedded.createDB(spec.databaseName());
                ds = pool(cfg.getURL(spec.databaseName()), "root", "", spec.databaseName());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to start embedded MariaDB", e);
            }
        }
        try {
            migratePristine(ds);
            List<String> tables = listTables(ds);
            return new DatabaseHandle(embedded, ds, tables, snapshotSeeds(ds, tables));
        } catch (RuntimeException | SQLException e) {
            ds.close();
            if (embedded != null) {
                try {
                    embedded.stop();
                } catch (Exception suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw new IllegalStateException("Failed to prepare test database " + spec.databaseName(), e);
        }
    }

    private static HikariDataSource pool(String url, String user, String pass, String poolName) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(pass);
        cfg.setMaximumPoolSize(POOL_SIZE);
        cfg.setPoolName(poolName + "-tests");
        return new HikariDataSource(cfg);
    }

    /**
     * Builds the schema from the authoritative economy-flyway migrations (staged onto
     * the consumer's test classpath at {@code db/migration}), so tests and production
     * share one schema source — triggers, views and UUID helpers included.
     *
     * <p>The schema must be <em>pristine</em>: the seed snapshot below is taken from
     * it, and a persistent local database may carry a previous run's rows or a
     * hand-altered schema. A fresh database (embedded mode, CI service containers)
     * migrates from nothing; anything else is cleaned and re-migrated. Cleaning is
     * refused unless the schema name contains {@code test}, so a mis-pointed
     * {@code *_TEST_JDBC_URL} can never wipe a real database.
     */
    private static void migratePristine(DataSource ds) throws SQLException {
        Flyway flyway = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        MigrateResult result = flyway.migrate();
        if (result.initialSchemaVersion == null) {
            return; // migrated from an empty schema: already pristine
        }
        String schema = currentSchema(ds);
        if (schema == null || !schema.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException("Refusing to clean schema '" + schema
                    + "': integration tests only reset databases whose name contains 'test'");
        }
        flyway.clean();
        flyway.migrate();
    }

    private static @Nullable String currentSchema(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT DATABASE()")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /**
     * The reset list is derived from the live schema rather than hand-maintained:
     * the old per-module lists silently skipped tables added by later migrations
     * ({@code explorer_audit}, the {@code chestshop_*} tables), so rows leaked from
     * one test into the next whenever the DB outlived a single class.
     */
    private static List<String> listTables(DataSource ds) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection c = ds.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT TABLE_NAME FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' "
                        + "AND TABLE_NAME <> ? ORDER BY TABLE_NAME")) {
            ps.setString(1, HISTORY_TABLE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tables.add(rs.getString(1));
            }
        }
        return List.copyOf(tables);
    }

    /**
     * Captures every row the migrations themselves inserted (the built-in explorer
     * role definitions/permissions, the webhook dispatch cursor, …) so a reset can
     * put them back. Generated columns are left out: they can't be written.
     */
    private static Map<String, SeedTable> snapshotSeeds(DataSource ds, List<String> tables) throws SQLException {
        Map<String, SeedTable> seeds = new LinkedHashMap<>();
        try (Connection c = ds.getConnection()) {
            for (String table : nonEmpty(c, tables)) {
                List<String> columns = writableColumns(c, table);
                String select = "SELECT " + String.join(", ", quoted(columns)) + " FROM " + q(table);
                List<Object[]> rows = new ArrayList<>();
                try (Statement st = c.createStatement();
                        ResultSet rs = st.executeQuery(select)) {
                    ResultSetMetaData md = rs.getMetaData();
                    while (rs.next()) {
                        Object[] row = new Object[md.getColumnCount()];
                        for (int i = 0; i < row.length; i++) row[i] = rs.getObject(i + 1);
                        rows.add(row);
                    }
                }
                seeds.put(table, new SeedTable(columns, List.copyOf(rows)));
            }
        }
        return Map.copyOf(seeds);
    }

    private static List<String> writableColumns(Connection c, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                + "AND EXTRA NOT LIKE '%GENERATED%' ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) columns.add(rs.getString(1));
            }
        }
        return columns;
    }

    /**
     * Returns every table to its just-migrated state. Only tables that are dirty —
     * holding rows, or with an AUTO_INCREMENT counter that has moved — are
     * truncated, which keeps the per-test cost to a handful of statements while still
     * covering every table in the schema. Seed tables are always rewritten from the
     * snapshot (they're tiny) so a test that edits a built-in role can't leak that
     * edit either. FK checks are off only for the duration of the reset.
     */
    void reset() throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement st = c.createStatement()) {
            Set<String> dirty = new LinkedHashSet<>(nonEmpty(c, tables));
            try (ResultSet rs = st.executeQuery("SELECT TABLE_NAME FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' "
                    + "AND AUTO_INCREMENT > 1")) {
                while (rs.next()) dirty.add(rs.getString(1));
            }
            dirty.remove(HISTORY_TABLE);
            dirty.removeAll(seeds.keySet());

            st.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                for (String table : dirty) {
                    st.execute("TRUNCATE TABLE " + q(table));
                }
                for (Map.Entry<String, SeedTable> seed : seeds.entrySet()) {
                    restore(c, seed.getKey(), seed.getValue());
                }
            } finally {
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        }
    }

    private static void restore(Connection c, String table, SeedTable seed) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("DELETE FROM " + q(table));
        }
        String placeholders =
                String.join(", ", java.util.Collections.nCopies(seed.columns().size(), "?"));
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + q(table) + " ("
                + String.join(", ", quoted(seed.columns())) + ") VALUES (" + placeholders + ")")) {
            for (Object[] row : seed.rows()) {
                for (int i = 0; i < row.length; i++) ps.setObject(i + 1, row[i]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** One round-trip: the names of the tables that currently hold at least one row. */
    private static List<String> nonEmpty(Connection c, List<String> tables) throws SQLException {
        if (tables.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder();
        for (String table : tables) {
            if (!sql.isEmpty()) sql.append(" UNION ALL ");
            sql.append("(SELECT '")
                    .append(table)
                    .append("' FROM ")
                    .append(q(table))
                    .append(" LIMIT 1)");
        }
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql.toString())) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static List<String> quoted(List<String> names) {
        return names.stream().map(DatabaseHandle::q).toList();
    }

    private static String q(String identifier) {
        return '`' + identifier.replace("`", "``") + '`';
    }

    @Override
    public void close() throws Exception {
        try {
            dataSource.close();
        } finally {
            if (embedded != null) embedded.stop();
        }
    }

    private record SeedTable(List<String> columns, List<Object[]> rows) {}
}
