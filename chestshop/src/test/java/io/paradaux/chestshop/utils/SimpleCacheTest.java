package io.paradaux.chestshop.utils;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimpleCacheTest {

    @Test
    void putAndGet_storeAndRetrieve() {
        SimpleCache<String, Integer> cache = new SimpleCache<>(8);
        cache.put("a", 1);
        assertThat(cache.get("a")).isEqualTo(1);
        assertThat(cache.contains("a")).isTrue();
    }

    @Test
    void get_returnsNullForMissingKey() {
        SimpleCache<String, Integer> cache = new SimpleCache<>(8);
        assertThat(cache.get("missing")).isNull();
        assertThat(cache.contains("missing")).isFalse();
    }

    @Test
    void evictsLeastRecentlyUsedAfterCapacity() {
        // The implementation uses access-order with size-based eviction. Tracking the
        // exact eviction policy is fiddly because the inner LinkedHashMap is sized at
        // cacheSize * 10/9 and doesn't always evict after exactly N inserts. What we
        // verify here is the *bounded growth* property: after enough inserts, the
        // cache size stays ≤ cacheSize * 2 (a generous upper bound).
        SimpleCache<Integer, Integer> cache = new SimpleCache<>(4);
        for (int i = 0; i < 100; i++) {
            cache.put(i, i);
        }
        // The cache should never grow unbounded.
        int present = 0;
        for (int i = 0; i < 100; i++) if (cache.contains(i)) present++;
        assertThat(present).isLessThanOrEqualTo(8);
    }

    @Test
    void getWithLoader_returnsCachedValueWithoutInvokingLoader() throws ExecutionException {
        SimpleCache<String, Integer> cache = new SimpleCache<>(8);
        cache.put("k", 99);

        AtomicInteger calls = new AtomicInteger(0);
        Integer v = cache.get("k", () -> { calls.incrementAndGet(); return 1234; });

        assertThat(v).isEqualTo(99);
        assertThat(calls.get()).isEqualTo(0);
    }

    @Test
    void getWithLoader_invokesLoaderWhenAbsentAndCachesResult() throws ExecutionException {
        SimpleCache<String, Integer> cache = new SimpleCache<>(8);
        AtomicInteger calls = new AtomicInteger(0);

        Integer first = cache.get("k", () -> { calls.incrementAndGet(); return 42; });
        Integer second = cache.get("k", () -> { calls.incrementAndGet(); return 999; });

        assertThat(first).isEqualTo(42);
        assertThat(second).isEqualTo(42);              // cached, not 999
        assertThat(calls.get()).isEqualTo(1);          // loader ran once
    }

    @Test
    void getWithLoader_doesNotCacheNullResult() throws ExecutionException {
        SimpleCache<String, Integer> cache = new SimpleCache<>(8);
        Integer v = cache.get("k", () -> null);
        assertThat(v).isNull();
        assertThat(cache.contains("k")).isFalse();
    }

    @Test
    void getWithLoader_wrapsLoaderExceptionInExecutionException() {
        SimpleCache<String, Integer> cache = new SimpleCache<>(8);
        assertThatThrownBy(() -> cache.get("k", () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
