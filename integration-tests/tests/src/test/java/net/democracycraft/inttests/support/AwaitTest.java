package net.democracycraft.inttests.support;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link Await} — no server needed. */
class AwaitTest {

    @Test
    void returnsTheFirstAcceptedValueWithoutWaitingWhenAlreadyTrue() {
        AtomicInteger reads = new AtomicInteger();
        long start = System.nanoTime();

        Integer v = Await.until("ready", () -> reads.incrementAndGet() * 10, x -> x >= 10, Duration.ofSeconds(5));

        assertThat(v).isEqualTo(10);
        assertThat(reads).hasValue(1);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));
    }

    @Test
    void pollsUntilTheConditionHolds() {
        AtomicInteger reads = new AtomicInteger();

        Integer v = Await.until("third read", reads::incrementAndGet, x -> x == 3, Duration.ofSeconds(5));

        assertThat(v).isEqualTo(3);
        assertThat(reads).hasValue(3);
    }

    @Test
    void timeoutNamesWhatWasAwaitedAndTheLastObservedValue() {
        AtomicInteger reads = new AtomicInteger();

        assertThatThrownBy(() -> Await.until("member count of account 7",
                () -> "count=" + reads.incrementAndGet(), s -> false, Duration.ofMillis(150)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("member count of account 7")
                .hasMessageContaining("PT0.15S")
                .hasMessageContaining("last observed: \"count=" + reads.get() + "\"");
    }

    @Test
    void timeoutReportsNullWhenNothingWasObserved() {
        assertThatThrownBy(() -> Await.until("a row", () -> null, v -> v != null, Duration.ofMillis(50)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("last observed: null");
    }

    @Test
    void readsAtLeastOnceEvenWithAZeroTimeout() {
        AtomicInteger reads = new AtomicInteger();

        assertThat(Await.until("instant", reads::incrementAndGet, x -> true, Duration.ZERO)).isEqualTo(1);
        assertThatThrownBy(() -> Await.untilTrue("never", () -> false, Duration.ZERO))
                .hasMessageContaining("1 reads in ");
    }

    @Test
    void untilEqualsDescribesTheExpectedValue() {
        assertThatThrownBy(() -> Await.untilEquals("status", () -> "PENDING", "DONE", Duration.ofMillis(30)))
                .hasMessageContaining("status to equal \"DONE\"")
                .hasMessageContaining("last observed: \"PENDING\"");
    }

    @Test
    void probeExceptionsPropagateInsteadOfBeingRetried() {
        AtomicInteger reads = new AtomicInteger();

        assertThatThrownBy(() -> Await.until("query", () -> {
            reads.incrementAndGet();
            throw new IllegalStateException("bad SQL");
        }, v -> true, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("bad SQL");
        assertThat(reads).hasValue(1);
    }

    @Test
    void backsOffBetweenReads() {
        AtomicInteger reads = new AtomicInteger();

        assertThatThrownBy(() -> Await.until("never", reads::incrementAndGet, x -> false, Duration.ofMillis(1000)))
                .isInstanceOf(AssertionError.class);
        // 10, 20, 40, 80, 160, 200, 200, 200... ms → about 9 reads in a second, not 100.
        assertThat(reads.get()).isBetween(5, 15);
    }
}
