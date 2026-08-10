package io.paradaux.treasuryapi.testsupport;

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
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * Base class for treasury-api-plugin mapper integration tests that hit the
 * embedded MariaDB.
 *
 * <p>The DB is started once per JVM and shared across every subclass. Each test
 * gets a clean slate via {@link MariadbContainerExtension#truncateAll} and a
 * single auto-commit {@link SqlSession} closed in {@link #tearDownIntegration()}.
 * The {@link UuidBinaryTypeHandler} is registered on the MyBatis configuration
 * exactly as the production {@code DatabaseModule} does, so UUID ↔ BINARY(16)
 * round-trips the same way the plugin runs it.
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
                // Same handler the production DatabaseModule registers — UUID
                // columns are BINARY(16) and only round-trip with it present.
                cfg.getTypeHandlerRegistry().register(UUID.class, new UuidBinaryTypeHandler());
                sharedFactory = new SqlSessionFactoryBuilder().build(cfg);
            }
            return sharedFactory;
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
