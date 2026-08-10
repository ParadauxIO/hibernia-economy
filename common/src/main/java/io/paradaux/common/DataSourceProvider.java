package io.paradaux.common;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/**
 * Single HikariCP-backed MariaDB {@link DataSource} factory shared by every plugin
 * that writes the economy DB (ADT-184). It was copy-pasted into Treasury, Business
 * and treasury-api-plugin with divergent, drifting pool/connection settings — one had
 * statement caching + leak detection, one set {@code READ COMMITTED} isolation, one had
 * neither. The shared, well-documented connection conventions live here; each plugin
 * declares only its own deltas via {@link Builder}.
 *
 * <p>Invariants enforced for all callers:
 * <ul>
 *   <li>MariaDB driver, UTF-8 connection charset.</li>
 *   <li>{@code autoCommit = false} — MyBatis / explicit transactions own commits.</li>
 * </ul>
 *
 * <p>This class is framework-free (no Paper/Guice/Spring) and depends only on HikariCP,
 * which every consuming plugin already bundles; {@code :common} keeps Hikari
 * {@code compileOnly} so it is not forced onto the Spring REST API's classpath.
 */
public final class DataSourceProvider implements AutoCloseable {

    private final HikariDataSource ds;

    /**
     * Assembles the MariaDB JDBC URL from the builder settings. Package-private and
     * side-effect-free so it can be unit-tested without standing up a pool/DB.
     *
     * <p>Driver-side statement caching + batch rewrite are opt-in: {@code cachePrepStmts}/
     * {@code useServerPrepStmts} let a connection reuse parsed statements, and
     * {@code rewriteBatchedStatements} turns a multi-row insert into one round-trip.
     * utf8 is always set for display names/memos.
     */
    static String buildJdbcUrl(String host, int port, String db, boolean statementCaching) {
        StringBuilder url = new StringBuilder()
                .append("jdbc:mariadb://").append(host).append(':').append(port)
                .append('/').append(db)
                .append("?useUnicode=true&characterEncoding=utf8");
        if (statementCaching) {
            url.append("&useServerPrepStmts=true&cachePrepStmts=true")
               .append("&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048")
               .append("&rewriteBatchedStatements=true");
        }
        return url.toString();
    }

    private DataSourceProvider(Builder b) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(buildJdbcUrl(b.host, b.port, b.db, b.statementCaching));
        cfg.setUsername(b.user);
        cfg.setPassword(b.pass);
        cfg.setMaximumPoolSize(b.maximumPoolSize);
        cfg.setMinimumIdle(b.minimumIdle);
        cfg.setPoolName(b.poolName);
        cfg.setDriverClassName("org.mariadb.jdbc.Driver");
        cfg.setAutoCommit(false); // Let MyBatis/transactions manage commits

        // Only override Hikari's own defaults when the caller supplied a value.
        if (b.connectionTimeoutMs >= 0) cfg.setConnectionTimeout(b.connectionTimeoutMs);
        if (b.maxLifetimeMs >= 0)       cfg.setMaxLifetime(b.maxLifetimeMs);
        if (b.keepaliveMs >= 0)         cfg.setKeepaliveTime(b.keepaliveMs);
        // With autoCommit=false a connection that escapes its transaction without a
        // commit/rollback (e.g. blocking I/O inside @Transactional) is pinned out of
        // the pool with an open transaction. A positive threshold warn-logs any
        // connection held longer than it so such leaks surface (ADT-55).
        if (b.leakDetectionThresholdMs > 0) cfg.setLeakDetectionThreshold(b.leakDetectionThresholdMs);
        if (b.transactionIsolation != null) cfg.setTransactionIsolation(b.transactionIsolation);

        this.ds = new HikariDataSource(cfg);
    }

    public DataSource get() { return ds; }

    public void close() { ds.close(); }

    public static Builder builder(String host, int port, String db, String user, String pass) {
        return new Builder(host, port, db, user, pass);
    }

    public static final class Builder {
        private final String host;
        private final int port;
        private final String db;
        private final String user;
        private final String pass;

        private String poolName = "Paper-Hikari";
        private int maximumPoolSize = 10;
        private int minimumIdle = 2;
        private boolean statementCaching = false;
        private String transactionIsolation = null; // driver default
        private long connectionTimeoutMs = -1;       // Hikari default (30s)
        private long maxLifetimeMs = -1;             // Hikari default (30m)
        private long keepaliveMs = -1;               // Hikari default (off)
        private long leakDetectionThresholdMs = 0;   // off

        private Builder(String host, int port, String db, String user, String pass) {
            this.host = host;
            this.port = port;
            this.db = db;
            this.user = user;
            this.pass = pass;
        }

        public Builder poolName(String v) { this.poolName = v; return this; }
        public Builder maximumPoolSize(int v) { this.maximumPoolSize = v; return this; }
        public Builder minimumIdle(int v) { this.minimumIdle = v; return this; }
        /** Append driver-side prepared-statement caching + batch-rewrite URL params. */
        public Builder statementCaching(boolean v) { this.statementCaching = v; return this; }
        /** e.g. {@code "TRANSACTION_READ_COMMITTED"}; null leaves the driver default. */
        public Builder transactionIsolation(String v) { this.transactionIsolation = v; return this; }
        public Builder connectionTimeoutMs(long v) { this.connectionTimeoutMs = v; return this; }
        public Builder maxLifetimeMs(long v) { this.maxLifetimeMs = v; return this; }
        public Builder keepaliveMs(long v) { this.keepaliveMs = v; return this; }
        public Builder leakDetectionThresholdMs(long v) { this.leakDetectionThresholdMs = v; return this; }

        public DataSourceProvider build() { return new DataSourceProvider(this); }
    }
}
