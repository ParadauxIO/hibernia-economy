package io.paradaux.hibernia.testsupport.db;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/**
 * JVM-wide registry of migrated test databases, one per {@link TestDatabase}.
 *
 * <p>Deliberately engine-agnostic (a static registry, not JUnit Jupiter's root
 * store): jqwik properties run on their own test engine where Jupiter extensions
 * never fire, yet must share the same pool and schema as the Jupiter ITs in the
 * same JVM. Handles are closed by a shutdown hook — the pool, then the embedded
 * server — which is also when MariaDB4j would tear its temp dir down anyway.
 *
 * <p>Jupiter tests normally use {@link TestDatabaseExtension}, which wraps this with
 * a per-test reset and the ledger invariant check; property tests call
 * {@link #reset(TestDatabase)} and {@link LedgerInvariants#assertHold} themselves.
 */
public final class TestDatabases {

    private static final Map<TestDatabase, DatabaseHandle> HANDLES = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> HANDLES.values().forEach(h -> {
                            try {
                                h.close();
                            } catch (Exception ignored) {
                                // JVM is exiting; nothing useful to do with a close failure.
                            }
                        }),
                        "test-databases-shutdown"));
    }

    private TestDatabases() {}

    /** The pooled, migrated DataSource for {@code spec}; boots/migrates on first use. */
    public static DataSource dataSource(TestDatabase spec) {
        return handle(spec).dataSource();
    }

    /** Truncates every dirty table and restores migration-seeded rows. */
    public static void reset(TestDatabase spec) {
        try {
            handle(spec).reset();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reset test database " + spec.databaseName(), e);
        }
    }

    static DatabaseHandle handle(TestDatabase spec) {
        return HANDLES.computeIfAbsent(spec, DatabaseHandle::open);
    }
}
