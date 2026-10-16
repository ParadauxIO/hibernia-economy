package io.paradaux.treasuryapi.testsupport;

import io.paradaux.hibernia.testsupport.db.TestDatabase;
import io.paradaux.hibernia.testsupport.db.TestDatabaseExtension;
import io.paradaux.treasuryapi.mappers.typehandlers.UuidBinaryTypeHandler;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * Base class for treasury-api-plugin mapper integration tests that hit a real
 * MariaDB — embedded MariaDB4j locally, or the server named by
 * {@code TREASURYAPI_TEST_JDBC_URL} — through the shared
 * {@link TestDatabaseExtension} harness in test-support.
 *
 * <p>The DB is started once per JVM and shared across every subclass. Each test
 * gets a clean slate (every table reset to its just-migrated state, the built-in
 * explorer role definitions restored), a post-test ledger invariant check, and a
 * single auto-commit {@link SqlSession} closed in {@link #tearDownIntegration()}.
 * The {@link UuidBinaryTypeHandler} is registered on the MyBatis configuration
 * exactly as the production {@code DatabaseModule} does, so UUID ↔ BINARY(16)
 * round-trips the same way the plugin runs it.
 */
@Tag("integration")
public abstract class IntegrationTestBase {

    public static final TestDatabase DATABASE = TestDatabase.of("treasuryapi_test", "TREASURYAPI");

    @RegisterExtension
    static final TestDatabaseExtension DB = new TestDatabaseExtension(DATABASE);

    private static volatile SqlSessionFactory sharedFactory;

    protected DataSource dataSource;
    protected SqlSessionFactory sqlSessionFactory;
    protected SqlSession sqlSession;

    @BeforeEach
    void setUpIntegration() {
        this.dataSource = DB.dataSource();
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
                // Same handler the production DatabaseModule registers — UUID
                // columns are BINARY(16) and only round-trip with it present.
                cfg.getTypeHandlerRegistry().register(UUID.class, new UuidBinaryTypeHandler());
                sharedFactory = new SqlSessionFactoryBuilder().build(cfg);
            }
            return sharedFactory;
        }
    }
}
