package io.paradaux.treasuryrestapi.service;

import static org.junit.jupiter.api.Assertions.*;

import io.paradaux.common.flags.FeatureFlagRuntime;
import io.paradaux.common.flags.FeatureFlagSnapshot;
import io.paradaux.common.flags.JdbcFeatureFlagReader;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class FeatureFlagServiceIT extends EmbeddedDbIT {
    @Test
    void governmentRegistrationDefaultsOffAndDoesNotOverwriteOperatorChoice() throws Exception {
        String migration;
        try (var in = getClass().getResourceAsStream("/db/migration/V53__government_api_keys_flag.sql")) {
            assertNotNull(in);
            migration = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        exec(migration, ps -> {});
        var reader = new JdbcFeatureFlagReader(dataSource);
        assertEquals(Boolean.FALSE, reader.read().get("treasury.government_api_keys"));
        exec("UPDATE feature_flags SET enabled = 1 WHERE name = 'treasury.government_api_keys'", ps -> {});
        exec(migration, ps -> {});
        assertEquals(Boolean.TRUE, reader.read().get("treasury.government_api_keys"));
    }

    @Test
    void defaultOffAndDatabaseConstraints() throws Exception {
        exec("INSERT INTO feature_flags (name, description) VALUES ('business.preview', 'Preview')", ps -> {});
        var reader = new JdbcFeatureFlagReader(dataSource);
        assertEquals(Boolean.FALSE, reader.read().get("business.preview"));
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            assertThrows(
                    java.sql.SQLException.class, () -> statement.executeUpdate("UPDATE feature_flags SET enabled = 2"));
            assertThrows(
                    java.sql.SQLException.class,
                    () -> statement.executeUpdate(
                            "INSERT INTO feature_flags (name, description) VALUES ('BAD.key', 'Invalid')"));
        }
        var snapshot = new FeatureFlagSnapshot();
        snapshot.publish(reader.read(), snapshot.loadStartedAt());
        assertFalse(snapshot.isEnabled("business.missing"));
    }

    @Test
    void independentConsumersObserveSqlEnablesDisablesAndDeletion() throws Exception {
        exec("INSERT INTO feature_flags (name, description) VALUES ('business.preview', 'Preview')", ps -> {});
        try (var paper = new FeatureFlagRuntime(dataSource, message -> {})) {
            var spring = new FeatureFlagService(dataSource);
            try {
                paper.start();
                spring.start();
                ApiException denied = assertThrows(ApiException.class, () -> spring.requireEnabled("business.preview"));
                assertEquals("FEATURE_DISABLED", denied.getErrorCode());
                assertEquals(404, denied.getStatus().value());
                exec("UPDATE feature_flags SET enabled = 1 WHERE name = 'business.preview'", ps -> {});
                await(() -> paper.isEnabled("business.preview") && spring.isEnabled("business.preview"));
                assertDoesNotThrow(() -> spring.requireEnabled("business.preview"));
                exec("UPDATE feature_flags SET enabled = 0 WHERE name = 'business.preview'", ps -> {});
                await(() -> !paper.isEnabled("business.preview") && !spring.isEnabled("business.preview"));
                exec("UPDATE feature_flags SET enabled = 1 WHERE name = 'business.preview'", ps -> {});
                await(() -> paper.isEnabled("business.preview") && spring.isEnabled("business.preview"));
                exec("DELETE FROM feature_flags WHERE name = 'business.preview'", ps -> {});
                await(() -> !paper.isEnabled("business.preview") && !spring.isEnabled("business.preview"));
            } finally {
                spring.close();
            }
            assertFalse(spring.isEnabled("business.preview"));
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(9).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), "flag change did not propagate within nine seconds");
    }
}
