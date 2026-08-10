package io.paradaux.business.testsupport;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

/**
 * Base class for integration tests that hit the embedded MariaDB.
 *
 * <p>The DB is started once per JVM and shared across every subclass. Each
 * test gets a clean slate via {@link MariadbContainerExtension#truncateAll}
 * and a single auto-commit {@link SqlSession} that is closed automatically
 * in {@link #tearDownIntegration()}. Tests should use {@link #mapper(Class)}
 * to fetch mappers from this shared session — that way connections never
 * leak even when assertions fail.
 */
@Tag("integration")
public abstract class IntegrationTestBase {

    @RegisterExtension
    static final ContextHolder CONTEXT = new ContextHolder();

    private static volatile SqlSessionFactory sharedFactory;

    protected DataSource dataSource;
    protected SqlSessionFactory sqlSessionFactory;
    protected SqlSession sqlSession;

    @BeforeEach
    void setUpIntegration() throws Exception {
        this.dataSource = MariadbContainerExtension.dataSource(CONTEXT.context);
        MariadbContainerExtension.truncateAll(dataSource);
        this.sqlSessionFactory = factory(dataSource);
        this.sqlSession = sqlSessionFactory.openSession(true);
    }

    @AfterEach
    void tearDownIntegration() {
        if (sqlSession != null) {
            sqlSession.close();
            sqlSession = null;
        }
    }

    /** Returns a mapper bound to the per-test session. */
    protected <T> T mapper(Class<T> mapperType) {
        if (!sqlSessionFactory.getConfiguration().hasMapper(mapperType)) {
            sqlSessionFactory.getConfiguration().addMapper(mapperType);
        }
        return sqlSession.getMapper(mapperType);
    }

    private static SqlSessionFactory factory(DataSource dataSource) {
        SqlSessionFactory existing = sharedFactory;
        if (existing != null) return existing;
        synchronized (IntegrationTestBase.class) {
            if (sharedFactory == null) {
                Configuration cfg = new Configuration(new Environment(
                        "test", new JdbcTransactionFactory(), dataSource));
                cfg.setMapUnderscoreToCamelCase(true);
                sharedFactory = new SqlSessionFactoryBuilder().build(cfg);
            }
            return sharedFactory;
        }
    }

    /**
     * Inserts a stub row in the Treasury {@code accounts} table that the FKs
     * on {@code firm.default_account_id} / {@code firm_accounts.account_id}
     * require to be present. A firm owns BUSINESS accounts, so stub one with the
     * NOT-NULL columns the real economy-flyway schema requires
     * ({@code account_type} + {@code owner_uuid_bin}). Returns the generated id.
     */
    protected int insertStubAccount(String displayName) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO accounts (account_type, owner_uuid_bin, display_name) "
                             + "VALUES ('BUSINESS', uuid_to_bin(?), ?)",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, displayName);
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Inserts a player row directly via JDBC; returns the same UUID. */
    protected UUID insertPlayer(String name) throws Exception {
        UUID id = UUID.randomUUID();
        insertPlayer(id, name);
        return id;
    }

    /** Inserts a player row with a caller-supplied UUID into the directory cache. */
    protected void insertPlayer(UUID id, String name) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO economy_players (player_uuid_bin, current_name) VALUES (uuid_to_bin(?), ?)")) {
            ps.setString(1, id.toString());
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    /** Captures an ExtensionContext so the static container helper can access JUnit's store. */
    static final class ContextHolder
            implements org.junit.jupiter.api.extension.BeforeAllCallback {
        ExtensionContext context;
        @Override public void beforeAll(ExtensionContext ctx) {
            this.context = ctx;
        }
    }
}
