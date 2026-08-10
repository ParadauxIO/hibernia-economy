package io.paradaux.treasuryrestapi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import java.util.function.Supplier;

/**
 * Shared retry-once wrapper for the check-then-insert idempotency race on
 * {@code client_dedup_key}.
 *
 * <p>{@link TransferService#executeTransfer} pre-checks the dedup key and then
 * inserts a {@code ledger_txns} row carrying it. That check-then-insert is not
 * atomic: two concurrent requests with the same {@code Idempotency-Key} (and the
 * same source account) can both pass the pre-check, after which the loser's insert
 * trips the {@code client_dedup_key} UNIQUE constraint and surfaces as a
 * {@link DuplicateKeyException}. By that point the winner has committed, so a
 * single retry re-runs the operation in a fresh transaction, finds the committed
 * row, and replays its cached response instead of 500-ing.
 *
 * <p>Both the token-scoped transfer path ({@code TransferController}) and the
 * SERVICE-scoped admin transfer path ({@code AdminTransferController}) route their
 * calls through here so the two entrypoints share one, identical replay behaviour.
 */
public final class IdempotencyReplay {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyReplay.class);

    private IdempotencyReplay() {
    }

    /**
     * Runs {@code op}, retrying exactly once if the first attempt loses the
     * check-then-insert race on {@code client_dedup_key}. The retry runs the
     * caller's supplier again — which, for a transfer, opens a fresh transaction
     * via the Spring proxy, finds the winner's committed row, and replays it.
     *
     * @throws DuplicateKeyException if the retry <em>also</em> throws it (should not
     *                               happen: the winner's row is committed by then)
     */
    public static <T> T withReplay(Supplier<T> op) {
        try {
            return op.get();
        } catch (DuplicateKeyException e) {
            log.info("Idempotency-Key insert raced a concurrent request; retrying once to replay the committed result");
            return op.get();
        }
    }
}
