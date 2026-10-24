package io.paradaux.treasury.model;

import io.paradaux.treasury.api.ingest.IngestReport;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.model.tax.TaxCollection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the hand-written record invariants on the published API surface — the
 * ADT-40 money-safety protections (amount &gt; 0, defensive dedup-key copies) and
 * the Page pagination math. These are the highest-value, lowest-cost tests the
 * dependency-light api module can carry, and were previously absent (ADT no-invariant-tests).
 */
class RecordInvariantsTest {

    private static final UUID U = UUID.randomUUID();

    // ---- TransferRequest ----

    @Test
    void transferRequest_rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> new TransferRequest(1, 2, BigDecimal.ZERO, "m", U, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TransferRequest(1, 2, new BigDecimal("-0.01"), "m", U, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transferRequest_rejectsNullMoneyFields() {
        assertThatThrownBy(() -> new TransferRequest(1, 2, null, "m", U, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransferRequest(1, 2, BigDecimal.ONE, null, U, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransferRequest(1, 2, BigDecimal.ONE, "m", null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void transferRequest_defensivelyCopiesDedupKey_inAndOut() {
        byte[] key = {1, 2, 3};
        TransferRequest r = new TransferRequest(1, 2, BigDecimal.ONE, "m", U, null, null, key);
        key[0] = 9; // mutating the caller's array must not affect the stored key
        assertThat(r.dedupKey()).containsExactly(1, 2, 3);
        r.dedupKey()[0] = 9; // mutating the returned array must not affect the stored key
        assertThat(r.dedupKey()).containsExactly(1, 2, 3);
    }

    // ---- TaxCollection ----

    @Test
    void taxCollection_rejectsNonPositiveAmount_andCopiesDedupKey() {
        assertThatThrownBy(() -> new TaxCollection(1, null, BigDecimal.ZERO, "t", "d", U, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] key = {7, 7};
        TaxCollection t = new TaxCollection(1, null, BigDecimal.ONE, "t", "d", U, null, key);
        key[0] = 0;
        assertThat(t.dedupKey()).containsExactly(7, 7);
    }

    // ---- Page ----

    @Test
    void page_wrapsNullItems_andIsUnmodifiable() {
        Page<String> empty = new Page<>(null, 0, 0, 10);
        assertThat(empty.items()).isEmpty();

        List<String> src = new ArrayList<>(List.of("a", "b"));
        Page<String> p = new Page<>(src, 5, 0, 2);
        src.add("c"); // mutating the source list must not affect the page
        assertThat(p.items()).containsExactly("a", "b");
        assertThatThrownBy(() -> p.items().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void page_paginationMath() {
        Page<String> p = new Page<>(List.of("a", "b"), 5, 0, 2);
        assertThat(p.hasMore()).isTrue();          // 0 + 2 < 5
        assertThat(p.pageNumber()).isEqualTo(1);
        assertThat(p.totalPages()).isEqualTo(3);   // ceil(5 / 2)

        Page<String> last = new Page<>(List.of("e"), 5, 4, 2);
        assertThat(last.hasMore()).isFalse();      // 4 + 1 == 5
        assertThat(last.pageNumber()).isEqualTo(3);

        Page<String> zeroLimit = new Page<>(List.of("a"), 1, 0, 0);
        assertThat(zeroLimit.totalPages()).isEqualTo(1); // guarded against divide-by-zero
        assertThat(zeroLimit.pageNumber()).isEqualTo(1);
    }

    // ---- IngestReport ----

    @Test
    void ingestReport_coalescesNullAmountToZero() {
        IngestReport r = new IngestReport("src", 0, 0, 0, 0, null, 0L);
        assertThat(r.totalIngestedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
