package io.paradaux.treasury.startup;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.paradaux.treasury.model.config.DatabaseConfiguration;
import org.junit.jupiter.api.Test;

class SchemaMigratorTest {
    static DatabaseConfiguration database() {
        var db = mock(DatabaseConfiguration.class);
        when(db.getHost()).thenReturn("localhost");
        when(db.getPort()).thenReturn("3306");
        when(db.getDatabase()).thenReturn("economy");
        when(db.getUsername()).thenReturn("app");
        when(db.getPassword()).thenReturn("app-password");
        when(db.getMigrationUsername()).thenReturn("");
        when(db.getMigrationPassword()).thenReturn("");
        return db;
    }

    @Test
    void preservesHistoryAndRefusesUnsafeAutomaticRecovery() {
        var config = SchemaMigrator.configure(database());
        assertThat(config.getTable()).isEqualTo("flyway_schema_history");
        assertThat(config.getSchemas()).containsExactly("economy");
        assertThat(config.isCleanDisabled()).isTrue();
        assertThat(config.isValidateOnMigrate()).isTrue();
        assertThat(config.isBaselineOnMigrate()).isFalse();
        assertThat(config.isOutOfOrder()).isFalse();
        assertThat(config.getIgnoreMigrationPatterns()).isEmpty();
        assertThat(config.getUser()).isEqualTo("app");
        assertThat(config.getPassword()).isEqualTo("app-password");
        assertThat(config.getLocations()).extracting(Object::toString).containsExactly("classpath:db/migration");
    }

    @Test
    void dedicatedCredentialsUseTheSameDatabase() {
        var db = database();
        when(db.getMigrationUsername()).thenReturn("migrator");
        when(db.getMigrationPassword()).thenReturn("ddl-password");
        var config = SchemaMigrator.configure(db);
        assertThat(config.getUser()).isEqualTo("migrator");
        assertThat(config.getPassword()).isEqualTo("ddl-password");
        assertThat(config.getUrl()).isEqualTo("jdbc:mariadb://localhost:3306/economy?connectTimeout=10000");
    }

    @Test
    void rejectsIncompleteCredentials() {
        var db = database();
        when(db.getMigrationUsername()).thenReturn("migrator");
        assertThatThrownBy(() -> SchemaMigrator.configure(db)).isInstanceOf(IllegalArgumentException.class);
        when(db.getMigrationUsername()).thenReturn("");
        when(db.getMigrationPassword()).thenReturn("ddl-password");
        assertThatThrownBy(() -> SchemaMigrator.configure(db)).isInstanceOf(IllegalArgumentException.class);
    }
}
