package net.democracycraft.inttests.support;

import java.time.Instant;

/**
 * Generates per-test-run unique usernames that satisfy Mojang's
 * {@code [A-Za-z0-9_]{3,16}} constraint.
 *
 * <p>The result is the prefix concatenated with a hex-encoded epoch-millis
 * suffix; both must fit in 16 chars, so prefixes should be ≤4 characters.
 */
public final class Usernames {

    public static String fresh(String prefix) {
        String suffix = Long.toHexString(Instant.now().toEpochMilli());
        String name = prefix + suffix;
        if (name.length() > 16) {
            throw new IllegalArgumentException(
                    "Username '" + name + "' exceeds 16 chars; use a shorter prefix (≤" + (16 - suffix.length()) + ")");
        }
        return name;
    }

    private Usernames() {}
}
