package net.democracycraft.inttests.support;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates per-test-run unique usernames that satisfy Mojang's
 * {@code [A-Za-z0-9_]{3,16}} constraint.
 *
 * <p>The result is the prefix concatenated with a hex-encoded monotonic
 * timestamp. Calls in the same millisecond (including concurrent calls) advance
 * the suffix, so separate fixtures cannot accidentally share a player identity.
 * Both must fit in 16 chars, so prefixes should be ≤4 characters.
 */
public final class Usernames {
    private static final AtomicLong LAST = new AtomicLong();

    public static String fresh(String prefix) {
        long timestamp = LAST.updateAndGet(previous -> Math.max(previous + 1, Instant.now().toEpochMilli()));
        String suffix = Long.toHexString(timestamp);
        String name = prefix + suffix;
        if (name.length() > 16) {
            throw new IllegalArgumentException(
                    "Username '" + name + "' exceeds 16 chars; use a shorter prefix (≤" + (16 - suffix.length()) + ")");
        }
        return name;
    }

    private Usernames() {}
}
