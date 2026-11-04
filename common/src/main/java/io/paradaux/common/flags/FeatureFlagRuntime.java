package io.paradaux.common.flags;

import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.jetbrains.annotations.Nullable;

/** One serial background reader per process; a failed refresh never renews state. */
public final class FeatureFlagRuntime implements AutoCloseable {
    private final FeatureFlagSnapshot snapshot;
    private final JdbcFeatureFlagReader reader;
    private final Consumer<String> log;
    private final ScheduledExecutorService worker;
    private @Nullable ScheduledFuture<?> task;
    private volatile boolean closed;
    private boolean failing;

    public FeatureFlagRuntime(DataSource dataSource, Consumer<String> log) {
        this(
                dataSource,
                log,
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "economy-feature-flags");
                    thread.setDaemon(true);
                    return thread;
                }),
                new FeatureFlagSnapshot());
    }

    FeatureFlagRuntime(
            DataSource dataSource,
            Consumer<String> log,
            ScheduledExecutorService worker,
            FeatureFlagSnapshot snapshot) {
        this.worker = worker;
        this.snapshot = snapshot;
        reader = new JdbcFeatureFlagReader(dataSource);
        this.log = log;
    }

    public synchronized void start() {
        if (task == null && !closed) task = worker.scheduleWithFixedDelay(this::refresh, 0, 5, TimeUnit.SECONDS);
    }

    public boolean isEnabled(String key) {
        return !closed && snapshot.isEnabled(key);
    }

    private void refresh() {
        long started = snapshot.loadStartedAt();
        try {
            var values = reader.read();
            if (!closed) snapshot.publish(values, started);
            if (failing) log.accept("Feature flag refresh recovered.");
            failing = false;
        } catch (SQLException | RuntimeException error) {
            if (!failing)
                log.accept(
                        "Feature flag refresh failed; enabled values expire after 30 seconds without a successful read.");
            failing = true;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        worker.shutdownNow();
        snapshot.clear();
    }
}
