package io.paradaux.treasury.model.config;

import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import lombok.Getter;

@ConfigurationComponent
@Getter
public class DatabaseConfiguration {

    @ConfigurationValue(path = "database.host", defaultValue = "localhost")
    private String host;

    @ConfigurationValue(path = "database.port", defaultValue = "3306")
    private String port;

    @ConfigurationValue(path = "database.database", defaultValue = "treasury")
    private String database;

    @ConfigurationValue(path = "database.username", defaultValue = "treasury")
    private String username;

    // Placeholder default; the plugin refuses to boot while it's unchanged
    // (ADT default-db-creds-in-shipped-config).
    @ConfigurationValue(path = "database.password", defaultValue = "CHANGE_ME")
    private String password;

    // ---- Connection pool (HikariCP) ----
    // Sized for a busy server; raise maximum-size to match DB capacity (and never
    // above what the DB's max_connections can serve across all clients).

    @ConfigurationValue(path = "database.pool.maximum-size", defaultValue = "30")
    private String poolMaximumSize;

    @ConfigurationValue(path = "database.pool.minimum-idle", defaultValue = "10")
    private String poolMinimumIdle;

    @ConfigurationValue(path = "database.pool.connection-timeout-ms", defaultValue = "10000")
    private String poolConnectionTimeoutMs;

    @ConfigurationValue(path = "database.pool.max-lifetime-ms", defaultValue = "1800000")
    private String poolMaxLifetimeMs;

    @ConfigurationValue(path = "database.pool.keepalive-ms", defaultValue = "300000")
    private String poolKeepaliveMs;

}
