package io.paradaux.chestshop.guice;

import com.google.inject.AbstractModule;
import io.paradaux.chestshop.mappers.AccountMapper;
import io.paradaux.chestshop.mappers.ItemCodeMapper;
import io.paradaux.chestshop.mappers.typehandlers.DateLongTypeHandler;
import io.paradaux.chestshop.mappers.typehandlers.UuidStringTypeHandler;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.SqlSessionManager;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

import java.io.File;

/**
 * Wires ChestShop's persistence: one MyBatis store per SQLite file ({@code users.db} for
 * accounts, {@code items.db} for item codes), binding each store's auto-committing mapper
 * proxy so services inject {@link AccountMapper}/{@link ItemCodeMapper} directly — the same
 * service→mapper shape the other plugins use, over SQLite rather than the shared MariaDB
 * (PAR-282). ChestShop keeps its two stores in separate files, so each gets its own
 * {@link SqlSessionManager} here rather than the single shared-MariaDB {@code MyBatisModule}
 * the other plugins use.
 *
 * <p>Each store's schema is owned by its mapper (the {@code createTable}/{@code create*Index}
 * {@code @Update}s): required statements run first (a failure aborts startup), then the
 * best-effort {@code ALTER}/index top-ups of a pre-existing older accounts schema (PAR-313).
 * The connection pools are held in {@link ChestShopDatabases} for {@code onDisable} close.
 */
public class DatabaseModule extends AbstractModule {

    private final File usersDatabase;
    private final File itemsDatabase;

    public DatabaseModule(File usersDatabase, File itemsDatabase) {
        this.usersDatabase = usersDatabase;
        this.itemsDatabase = itemsDatabase;
    }

    @Override
    protected void configure() {
        PooledDataSource usersPool = pool(usersDatabase);
        PooledDataSource itemsPool = pool(itemsDatabase);

        AccountMapper accounts = mapper(usersPool, AccountMapper.class);
        ItemCodeMapper items = mapper(itemsPool, ItemCodeMapper.class);

        // Schema lives on the mappers now: required tables/indexes, then best-effort top-ups
        // of a pre-existing older accounts schema (ignored when already applied).
        accounts.createTable();
        items.createTable();
        items.createCodeIndex();
        bestEffort(accounts::addLastSeenColumn);
        bestEffort(accounts::addIgnoreMessagesColumn);
        bestEffort(accounts::createNameUuidIndex);

        bind(AccountMapper.class).toInstance(accounts);
        bind(ItemCodeMapper.class).toInstance(items);
        bind(ChestShopDatabases.class).toInstance(new ChestShopDatabases(usersPool, itemsPool));
    }

    /** A single-connection SQLite pool — SQLite is single-writer, so all access is serialised. */
    private static PooledDataSource pool(File dbFile) {
        PooledDataSource pool = new PooledDataSource(
                "org.sqlite.JDBC", "jdbc:sqlite:" + dbFile.getAbsolutePath(), null, null);
        pool.setPoolMaximumActiveConnections(1);
        pool.setPoolMaximumIdleConnections(1);
        return pool;
    }

    /**
     * Build an auto-committing mapper proxy over {@code pool}. The {@link SqlSessionManager}
     * opens and commits a session per mapper call, so an injected mapper "just works" without a
     * transaction interceptor (mirroring mybatis-guice on the other plugins).
     */
    private static <M> M mapper(PooledDataSource pool, Class<M> mapperClass) {
        Configuration configuration = new Configuration(
                new Environment("chestshop-sqlite", new JdbcTransactionFactory(), pool));
        configuration.getTypeHandlerRegistry().register(new UuidStringTypeHandler());
        configuration.getTypeHandlerRegistry().register(new DateLongTypeHandler());
        configuration.addMapper(mapperClass);

        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        return SqlSessionManager.newInstance(factory).getMapper(mapperClass);
    }

    /** Run a best-effort schema top-up, swallowing "already applied" failures. */
    private static void bestEffort(Runnable ddl) {
        try {
            ddl.run();
        } catch (RuntimeException ignored) {
            // e.g. ADD COLUMN on a DB that already has it, or a unique index over legacy dupes.
        }
    }
}
