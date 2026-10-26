package io.paradaux.treasury.startup;

import io.paradaux.treasury.model.config.DatabaseConfiguration;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/** The same immutable SQL and history table used by the administrative Flyway tasks. */
public final class SchemaMigrator {
    private SchemaMigrator() {}

    public static FluentConfiguration configure(DatabaseConfiguration database) {
        String user = database.getMigrationUsername();
        String password = database.getMigrationPassword();
        if (user.isBlank() != password.isBlank()) {
            throw new IllegalArgumentException("Configure both database.migrations.username and password, or neither");
        }
        if (user.isBlank()) {
            user = database.getUsername();
            password = database.getPassword();
        }
        String url = "jdbc:mariadb://" + database.getHost() + ":" + Integer.parseInt(database.getPort()) + "/"
                + database.getDatabase() + "?connectTimeout=10000";
        return Flyway.configure(SchemaMigrator.class.getClassLoader())
                .dataSource(url, user, password)
                .schemas(database.getDatabase())
                .locations("classpath:db/migration")
                .table("flyway_schema_history")
                .validateOnMigrate(true)
                // Also reject a database newer than this release (Flyway normally ignores future migrations).
                .ignoreMigrationPatterns(new String[0])
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .cleanDisabled(true);
    }
}
