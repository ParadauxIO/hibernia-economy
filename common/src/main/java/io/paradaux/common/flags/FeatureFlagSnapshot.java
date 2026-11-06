package io.paradaux.common.flags;

import java.time.Duration;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Atomic, fail-closed release state. Reads never perform I/O. */
public final class FeatureFlagSnapshot {
    public static final Duration MAX_AGE = Duration.ofSeconds(30);
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_.]*");
    private final LongSupplier clock;
    private volatile State state = new State(Map.of(), 0, false);

    public FeatureFlagSnapshot() {
        this(System::nanoTime);
    }

    public FeatureFlagSnapshot(LongSupplier clock) {
        this.clock = clock;
    }

    public static boolean validKey(String key) {
        return key.length() <= 128 && KEY.matcher(key).matches();
    }

    /** Capture before loading, so a delayed query cannot renew old enabled values. */
    public long loadStartedAt() {
        return clock.getAsLong();
    }

    public void publish(Map<String, Boolean> values, long startedAt) {
        state = new State(Map.copyOf(values), startedAt, true);
    }

    public boolean isEnabled(String key) {
        State current = state;
        long age = clock.getAsLong() - current.loadedAt();
        return validKey(key)
                && current.loaded()
                && age >= 0
                && age < MAX_AGE.toNanos()
                && Boolean.TRUE.equals(current.values().get(key));
    }

    public void clear() {
        state = new State(Map.of(), 0, false);
    }

    private record State(Map<String, Boolean> values, long loadedAt, boolean loaded) {}
}
