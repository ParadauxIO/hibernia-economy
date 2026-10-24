package net.democracycraft.inttests.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link Await} — no server needed. */
class AwaitTest {
    private final FakeTime time = new FakeTime();

    private <T> T until(String what, Supplier<T> probe, Predicate<? super T> done, Duration timeout) {
        return Await.until(what, probe, done, timeout, () -> time.nanos, time::sleep);
    }

    private static final class FakeTime {
        long nanos;
        final List<Long> sleeps = new ArrayList<>();

        void sleep(long millis) {
            sleeps.add(millis);
            nanos += Duration.ofMillis(millis).toNanos();
        }
    }

    @Test
    void returnsTheFirstAcceptedValueWithoutWaitingWhenAlreadyTrue() {
        AtomicInteger reads = new AtomicInteger();

        Integer v = until("ready", () -> reads.incrementAndGet() * 10, x -> x >= 10, Duration.ofSeconds(5));

        assertThat(v).isEqualTo(10);
        assertThat(reads).hasValue(1);
        assertThat(time.sleeps).isEmpty();
    }

    @Test
    void pollsUntilTheConditionHolds() {
        AtomicInteger reads = new AtomicInteger();

        Integer v = until("third read", reads::incrementAndGet, x -> x == 3, Duration.ofSeconds(5));

        assertThat(v).isEqualTo(3);
        assertThat(reads).hasValue(3);
        assertThat(time.sleeps).containsExactly(10L, 20L);
    }

    @Test
    void timeoutNamesWhatWasAwaitedAndTheLastObservedValue() {
        AtomicInteger reads = new AtomicInteger();

        assertThatThrownBy(() -> until("member count of account 7",
                () -> "count=" + reads.incrementAndGet(), s -> false, Duration.ofMillis(150)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("member count of account 7")
                .hasMessageContaining("PT0.15S")
                .hasMessageContaining("last observed: \"count=" + reads.get() + "\"");
    }

    @Test
    void timeoutReportsNullWhenNothingWasObserved() {
        assertThatThrownBy(() -> until("a row", () -> null, v -> v != null, Duration.ofMillis(50)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("last observed: null");
    }

    @Test
    void readsAtLeastOnceEvenWithAZeroTimeout() {
        AtomicInteger reads = new AtomicInteger();

        assertThat(until("instant", reads::incrementAndGet, x -> true, Duration.ZERO)).isEqualTo(1);
        assertThatThrownBy(() -> Await.untilTrue("never", () -> false, Duration.ZERO))
                .hasMessageContaining("1 reads in ");
    }

    @Test
    void untilEqualsDescribesTheExpectedValue() {
        assertThatThrownBy(() -> Await.untilEquals("status", () -> "PENDING", "DONE", Duration.ZERO))
                .hasMessageContaining("status to equal \"DONE\"")
                .hasMessageContaining("last observed: \"PENDING\"");
    }

    @Test
    void probeExceptionsPropagateInsteadOfBeingRetried() {
        AtomicInteger reads = new AtomicInteger();

        assertThatThrownBy(() -> until("query", () -> {
            reads.incrementAndGet();
            throw new IllegalStateException("bad SQL");
        }, v -> true, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("bad SQL");
        assertThat(reads).hasValue(1);
    }

    @Test
    void slowProbeConsumesTheTimeoutWithoutAnExtraSleep() {
        AtomicInteger reads = new AtomicInteger();
        assertThatThrownBy(() -> until("slow query", () -> {
            reads.incrementAndGet();
            time.nanos += Duration.ofSeconds(2).toNanos();
            return false;
        }, value -> value, Duration.ofSeconds(1)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("1 reads in 2000 ms");
        assertThat(time.sleeps).isEmpty();
        assertThat(reads).hasValue(1);
    }

    @Test
    void backsOffBetweenReads() {
        AtomicInteger reads = new AtomicInteger();

        assertThatThrownBy(() -> until("never", reads::incrementAndGet, x -> false, Duration.ofMillis(1000)))
                .isInstanceOf(AssertionError.class);
        // Exact backoff, capped at 200ms, with the last sleep bounded by the deadline.
        assertThat(time.sleeps).containsExactly(10L, 20L, 40L, 80L, 160L, 200L, 200L, 200L, 90L);
        assertThat(reads).hasValue(10);
        assertThat(time.nanos).isEqualTo(Duration.ofSeconds(1).toNanos());
    }
}
