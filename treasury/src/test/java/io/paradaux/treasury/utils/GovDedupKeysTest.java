package io.paradaux.treasury.utils;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the dedup-key collision bug in {@code /government pay} and
 * {@code /government payout}. Pre-fix, the key was {@code "gov-transfer:" + sender +
 * ":" + secondTimestamp}, so two distinct transfers from the same sender within the
 * same second collapsed into one (the second silently became a no-op).
 *
 * <p>Post-fix, the key includes from/to/amount, so only a genuine replay (same sender,
 * same accounts, same amount, same second) dedupes.
 */
class GovDedupKeysTest {

    private static final UUID SENDER  = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER   = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant NOW  = Instant.parse("2026-01-15T12:34:56Z");
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    // ---- transfer ----

    @Test
    void transfer_sameInputs_produceIdenticalKey() {
        byte[] a = GovDedupKeys.transfer(SENDER, 1, 2, AMOUNT, NOW);
        byte[] b = GovDedupKeys.transfer(SENDER, 1, 2, AMOUNT, NOW);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void transfer_differentSender_produceDifferentKeys() {
        byte[] a = GovDedupKeys.transfer(SENDER, 1, 2, AMOUNT, NOW);
        byte[] b = GovDedupKeys.transfer(OTHER,  1, 2, AMOUNT, NOW);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void transfer_differentSourceAccount_produceDifferentKeys() {
        byte[] a = GovDedupKeys.transfer(SENDER, 1, 2, AMOUNT, NOW);
        byte[] b = GovDedupKeys.transfer(SENDER, 3, 2, AMOUNT, NOW);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void transfer_differentDestinationAccount_produceDifferentKeys() {
        byte[] a = GovDedupKeys.transfer(SENDER, 1, 2, AMOUNT, NOW);
        byte[] b = GovDedupKeys.transfer(SENDER, 1, 4, AMOUNT, NOW);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void transfer_differentAmount_produceDifferentKeys() {
        byte[] a = GovDedupKeys.transfer(SENDER, 1, 2, AMOUNT, NOW);
        byte[] b = GovDedupKeys.transfer(SENDER, 1, 2, new BigDecimal("100.01"), NOW);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void transfer_differentSecond_produceDifferentKeys() {
        byte[] a = GovDedupKeys.transfer(SENDER, 1, 2, AMOUNT, NOW);
        byte[] b = GovDedupKeys.transfer(SENDER, 1, 2, AMOUNT, NOW.plusSeconds(1));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void transfer_truncatesSubSecondPrecision() {
        // Within the same second, sub-second differences must collapse so a
        // legitimate button-mash dedupes correctly.
        byte[] a = GovDedupKeys.transfer(SENDER, 1, 2, AMOUNT, NOW);
        byte[] b = GovDedupKeys.transfer(SENDER, 1, 2, AMOUNT, NOW.plusMillis(750));
        assertThat(a).isEqualTo(b);
    }

    // ---- payout ----

    @Test
    void payout_sameInputs_produceIdenticalKey() {
        byte[] a = GovDedupKeys.payout(SENDER, 1, 2, AMOUNT, NOW);
        byte[] b = GovDedupKeys.payout(SENDER, 1, 2, AMOUNT, NOW);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void payout_namespaceDiffersFromTransfer() {
        // Even with all other args identical, payout and transfer must occupy
        // separate dedup namespaces — the prefix differentiates them.
        byte[] t = GovDedupKeys.transfer(SENDER, 1, 2, AMOUNT, NOW);
        byte[] p = GovDedupKeys.payout(SENDER,   1, 2, AMOUNT, NOW);
        assertThat(t).isNotEqualTo(p);
    }

    @Test
    void payout_differentArgsProduceDifferentKeys() {
        byte[] a = GovDedupKeys.payout(SENDER, 1, 2, AMOUNT, NOW);
        byte[] b = GovDedupKeys.payout(SENDER, 1, 3, AMOUNT, NOW);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void allKeysAreThirtyTwoBytes() {
        assertThat(GovDedupKeys.transfer(SENDER, 1, 2, AMOUNT, NOW)).hasSize(32);
        assertThat(GovDedupKeys.payout(SENDER, 1, 2, AMOUNT, NOW)).hasSize(32);
        assertThat(GovDedupKeys.adminTransfer(SENDER, 1, 2, AMOUNT, NOW)).hasSize(32);
    }

    // ---- admin transfer ----

    @Test
    void adminTransfer_sameInputs_produceIdenticalKey() {
        byte[] a = GovDedupKeys.adminTransfer(SENDER, 1, 2, AMOUNT, NOW);
        byte[] b = GovDedupKeys.adminTransfer(SENDER, 1, 2, AMOUNT, NOW);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void adminTransfer_differentArgsProduceDifferentKeys() {
        byte[] a = GovDedupKeys.adminTransfer(SENDER, 1, 2, AMOUNT, NOW);
        byte[] b = GovDedupKeys.adminTransfer(SENDER, 1, 3, AMOUNT, NOW);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void adminTransfer_namespaceDiffersFromTransferAndPayout() {
        byte[] admin = GovDedupKeys.adminTransfer(SENDER, 1, 2, AMOUNT, NOW);
        byte[] t     = GovDedupKeys.transfer(SENDER, 1, 2, AMOUNT, NOW);
        byte[] p     = GovDedupKeys.payout(SENDER, 1, 2, AMOUNT, NOW);
        assertThat(admin).isNotEqualTo(t).isNotEqualTo(p);
    }
}
