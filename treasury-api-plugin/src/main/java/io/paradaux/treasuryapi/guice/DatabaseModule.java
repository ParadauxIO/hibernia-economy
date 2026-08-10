package io.paradaux.treasuryapi.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import io.paradaux.common.DataSourceProvider;
import io.paradaux.treasuryapi.mappers.ApiKeyMapper;
import io.paradaux.treasuryapi.mappers.ExplorerGroupMapper;
import io.paradaux.treasuryapi.mappers.ExplorerUiMapper;
import io.paradaux.treasuryapi.mappers.typehandlers.UuidBinaryTypeHandler;
import io.paradaux.treasuryapi.model.config.DatabaseConfiguration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.mybatis.guice.MyBatisModule;
import org.mybatis.guice.datasource.helper.JdbcHelper;

import javax.sql.DataSource;

public final class DatabaseModule extends AbstractModule {

    private final DatabaseConfiguration databaseConfig;

    @Inject
    public DatabaseModule(DatabaseConfiguration databaseConfig) {
        this.databaseConfig = databaseConfig;
    }

    @Override
    protected void configure() {
        install(new MyBatisModule() {
            @Override
            protected void initialize() {
                // MariaDB is MySQL-ish — use MySQL helper for dialect defaults
                install(JdbcHelper.MySQL);

                bindTransactionFactoryType(JdbcTransactionFactory.class);

                addMapperClass(ApiKeyMapper.class);
                addMapperClass(ExplorerUiMapper.class);
                addMapperClass(ExplorerGroupMapper.class);

                addTypeHandlerClass(UuidBinaryTypeHandler.class);

                environmentId("paper-mybatis");
                bindConstant().annotatedWith(Names.named("mybatis.configuration.mapUnderscoreToCamelCase"))
                        .to(true);
            }
        });
    }

    @Provides
    @Singleton
    DataSource provideDataSource() {
        // Env vars take precedence over config.yml so secrets can be injected
        // (k8s secret / chmod-ed env) rather than committed in plaintext (ADT-38).
        String host = env("TREASURYAPI_DB_HOST", databaseConfig.getHost());
        int port = Integer.parseInt(env("TREASURYAPI_DB_PORT", databaseConfig.getPort()));
        String db = env("TREASURYAPI_DB_NAME", databaseConfig.getDatabase());
        String user = env("TREASURYAPI_DB_USERNAME", databaseConfig.getUsername());
        String pass = env("TREASURYAPI_DB_PASSWORD", databaseConfig.getPassword());
        // Fail fast instead of silently shipping the documented default password.
        if ("password".equals(pass)) {
            throw new IllegalStateException(
                    "Refusing to start: the database password is still the insecure default. "
                    + "Set database.password in config.yml or the TREASURYAPI_DB_PASSWORD env var.");
        }
        return DataSourceProvider.builder(host, port, db, user, pass).build().get();
    }

    /** Prefer a non-blank environment variable over the config value (secret injection). */
    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }
}
