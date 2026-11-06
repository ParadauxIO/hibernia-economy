package io.paradaux.common.flags;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FeatureFlagSnapshotTest {
    @Test
    void sharedContract() throws Exception {
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/feature-flags.tsv"))) {
            for (String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("#") || line.isBlank()) continue;
                String[] row = line.split("\t");
                AtomicLong now = new AtomicLong();
                var flags = new FeatureFlagSnapshot(now::get);
                flags.publish(Map.of(row[0], row[1].equals("1")), 0);
                now.set(Long.parseLong(row[2]) * 1_000_000L);
                assertEquals(Boolean.parseBoolean(row[3]), flags.isEnabled(row[0]), line);
            }
        }
    }

    @Test
    void missingDeletionExpiryAndRecovery() {
        AtomicLong now = new AtomicLong();
        var flags = new FeatureFlagSnapshot(now::get);
        assertFalse(flags.isEnabled("business.preview"));
        flags.publish(Map.of("business.preview", true), flags.loadStartedAt());
        assertTrue(flags.isEnabled("business.preview"));
        assertFalse(flags.isEnabled("business.missing"));
        now.set(30_000_000_000L); // failed/stalled refresh never publishes
        assertFalse(flags.isEnabled("business.preview"));
        flags.publish(Map.of("business.preview", true), flags.loadStartedAt());
        assertTrue(flags.isEnabled("business.preview"));
        flags.publish(Map.of(), flags.loadStartedAt());
        assertFalse(flags.isEnabled("business.preview"));
        flags.clear();
        assertFalse(flags.isEnabled("business.preview"));
    }

    @Test
    void delayedReadCannotRenewOldEnabledStateAndInputMapIsCopied() {
        AtomicLong now = new AtomicLong();
        var flags = new FeatureFlagSnapshot(now::get);
        var values = new java.util.HashMap<String, Boolean>();
        values.put("business.preview", true);
        flags.publish(values, 0);
        values.clear();
        assertTrue(flags.isEnabled("business.preview"));
        now.set(31_000_000_000L);
        flags.publish(Map.of("business.preview", true), 0);
        assertFalse(flags.isEnabled("business.preview"));
    }
}
