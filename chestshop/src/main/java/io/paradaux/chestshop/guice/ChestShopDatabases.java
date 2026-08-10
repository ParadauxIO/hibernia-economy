package io.paradaux.chestshop.guice;

import org.apache.ibatis.datasource.pooled.PooledDataSource;

import java.util.List;

/**
 * Holds the SQLite connection pools opened by {@link DatabaseModule} so the plugin can close
 * them on {@code onDisable} (replacing the old static {@code DaoCreator.closeAll()}).
 */
public final class ChestShopDatabases {

    private final List<PooledDataSource> pools;

    public ChestShopDatabases(PooledDataSource... pools) {
        this.pools = List.of(pools);
    }

    /** Close every connection pool. */
    public void close() {
        pools.forEach(PooledDataSource::forceCloseAll);
    }
}
