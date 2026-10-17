package io.paradaux.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the pure JDBC-URL assembly of {@link DataSourceProvider} without a live
 * MariaDB (HikariCP 7 eagerly seeds its pool in the constructor, so a full
 * {@code build()} needs a reachable DB — an integration concern). The URL is the
 * factory's plugin-visible contract (ADT-184): the MariaDB URL shape, the always-on
 * UTF-8 charset, and the opt-in statement-caching / batch-rewrite params.
 *
 * <p>The {@code autoCommit=false} invariant and pool wiring are enforced against a
 * real pool in the MariaDB integration tests; here we pin only what is verifiable
 * offline.
 */
class DataSourceProviderTest {

    @Test
    void buildsMariaDbUrlWithUtf8Charset() {
        String url = DataSourceProvider.buildJdbcUrl("db.internal", 3306, "economy", false);
        assertTrue(url.startsWith("jdbc:mariadb://db.internal:3306/economy"), url);
        assertTrue(url.contains("useUnicode=true"), url);
        assertTrue(url.contains("characterEncoding=utf8"), url);
    }

    @Test
    void hostPortAndDbFlowIntoTheAuthority() {
        String url = DataSourceProvider.buildJdbcUrl("10.0.0.5", 3307, "ledger", false);
        assertTrue(url.startsWith("jdbc:mariadb://10.0.0.5:3307/ledger?"), url);
    }

    @Test
    void statementCachingOff_omitsCacheAndBatchParams() {
        String url = DataSourceProvider.buildJdbcUrl("h", 3306, "d", false);
        assertFalse(url.contains("cachePrepStmts"), url);
        assertFalse(url.contains("useServerPrepStmts"), url);
        assertFalse(url.contains("rewriteBatchedStatements"), url);
    }

    @Test
    void statementCachingOn_appendsCacheAndBatchRewriteParams() {
        String url = DataSourceProvider.buildJdbcUrl("h", 3306, "d", true);
        assertTrue(url.contains("useServerPrepStmts=true"), url);
        assertTrue(url.contains("cachePrepStmts=true"), url);
        assertTrue(url.contains("prepStmtCacheSize=250"), url);
        assertTrue(url.contains("prepStmtCacheSqlLimit=2048"), url);
        assertTrue(url.contains("rewriteBatchedStatements=true"), url);
        // caching params are appended to, not a replacement of, the base charset URL
        assertTrue(url.contains("characterEncoding=utf8"), url);
    }

    @Test
    void builderDefaults_produceANonCachingUrl() {
        // The builder's default (statementCaching=false) must yield the plain URL —
        // guards against the default flipping to the heavier caching URL unnoticed.
        String defaultUrl = DataSourceProvider.buildJdbcUrl("h", 3306, "d", false);
        assertEquals("jdbc:mariadb://h:3306/d?useUnicode=true&characterEncoding=utf8", defaultUrl);
    }
}
