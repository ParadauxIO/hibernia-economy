package io.paradaux.business.model.config;

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
}
