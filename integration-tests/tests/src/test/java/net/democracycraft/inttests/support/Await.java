package net.democracycraft.inttests.support;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The suite's one polling primitive: re-read a value until it satisfies a condition, or
 * fail with what was last observed.
 *
 * <pre>{@code
 * BigDecimal bal = Await.until("personal balance of " + uuid,
 *         () -> Ledger.personalBalance(uuid).orElse(null),
 *         b -> b != null && b.compareTo(expected) == 0,
 *         TIMEOUT);
 * }</pre>
 *
 * <p>Use it for state that converges on its own (a DB row written by an async handler, a
 * LuckPerms grant applied on LuckPerms' thread). It is <em>not</em> a way to show that
 * something did not happen: polling "still unchanged" passes the moment you start. For
 * that, run a positive barrier first ({@link Barrier}) and then assert once.
 *
 * <p>Polling starts at {@value #FIRST_POLL_MS} ms and backs off to {@value #MAX_POLL_MS} ms,
 * so a condition that is already true (the common case after a barrier) costs one read.
 * Exceptions thrown by the probe propagate — a failing query is a test bug, not "not yet".
 */
public final class Await {

    static final long FIRST_POLL_MS = 10;
    static final long MAX_POLL_MS = 200;

    /**
     * Polls {@code probe} until {@code done} accepts its value; returns that value.
     *
     * @param what    what is being waited for, for the failure message
     *                (e.g. {@code "member count of account 12"})
     * @throws AssertionError on timeout, naming {@code what}, the timeout, the number of
     *                        reads and the last value observed
     */
    public static <T> T until(String what, Supplier<T> probe, Predicate<? super T> done, Duration timeout) {
        Objects.requireNonNull(what, "what");
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(done, "done");
        Objects.requireNonNull(timeout, "timeout");

        long start = System.nanoTime();
        long deadline = start + timeout.toNanos();
        long pause = FIRST_POLL_MS;
        int reads = 0;
        T last;
        while (true) {
            last = probe.get();
            reads++;
            if (done.test(last)) return last;
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
            if (remainingMs <= 0) break;
            sleep(Math.min(pause, remainingMs));
            pause = Math.min(pause * 2, MAX_POLL_MS);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        throw new AssertionError("Timed out after " + timeout + " waiting for " + what
                + " (" + reads + " reads in " + elapsedMs + " ms; last observed: " + describe(last) + ")");
    }

    /** Polls until {@code probe} returns a value equal to {@code expected}; returns it. */
    public static <T> T untilEquals(String what, Supplier<T> probe, T expected, Duration timeout) {
        return until(what + " to equal " + describe(expected), probe, v -> Objects.equals(v, expected), timeout);
    }

    /** Polls until {@code condition} holds. */
    public static void untilTrue(String what, BooleanSupplier condition, Duration timeout) {
        until(what, condition::getAsBoolean, Boolean::booleanValue, timeout);
    }

    private static String describe(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return '"' + s + '"';
        return value.toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", e);
        }
    }

    private Await() {}
}
