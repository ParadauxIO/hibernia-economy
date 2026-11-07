package io.paradaux.business.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.internal.model.directdebit.CollectionStatus;
import io.paradaux.business.internal.model.directdebit.DirectDebitCollection;
import io.paradaux.business.internal.model.directdebit.DirectDebitKind;
import io.paradaux.business.internal.model.directdebit.DirectDebitMandate;
import io.paradaux.business.internal.model.directdebit.DirectDebitStatus;
import io.paradaux.business.mappers.DirectDebitMapper;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.ExceedsLimitException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.mybatis.guice.transactional.Transactional;

/**
 * The database halves of a direct-debit collection (PAR-557), each its own
 * transaction under the mandate's row lock. A collection is reserved and
 * committed <em>before</em> the ledger transfer (so it counts against the cap even
 * if the process dies mid-charge) and settled after it. Kept apart from
 * {@link DirectDebitServiceImpl} because Guice's {@code @Transactional} only
 * applies to calls made through the injected proxy.
 */
@Singleton
public class DirectDebitStore {

    /** Attempts at one recurring period before it counts as missed. */
    static final int MAX_ATTEMPTS_PER_PERIOD = 3;

    /** Consecutive missed periods before a recurring mandate is suspended. */
    static final int MAX_MISSED_PERIODS = 3;

    /** Delay between attempts at a failed recurring period. */
    static final java.time.Duration RETRY_DELAY = java.time.Duration.ofHours(1);

    private final DirectDebitMapper mapper;

    @Inject
    public DirectDebitStore(DirectDebitMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * A committed reservation: the mandate as locked, the RESERVED collection and, for
     * on-demand charges, the cap left in the period once it settles (else {@code null}).
     */
    public record Reservation(DirectDebitMandate mandate, DirectDebitCollection collection, BigDecimal remaining) {
        public Reservation(DirectDebitMandate mandate, DirectDebitCollection collection) {
            this(mandate, collection, null);
        }
    }

    /** What settling a recurring collection did to its mandate. */
    public enum RecurringOutcome {
        COLLECTED,
        RETRY_SCHEDULED,
        PERIOD_MISSED,
        SUSPENDED
    }

    /**
     * Reserves an on-demand charge if the mandate belongs to {@code firmId}, is
     * active, and the period's reserved plus collected total stays within the cap.
     */
    @Transactional
    public Reservation reserveOnDemand(
            int firmId, long mandateId, String initiatorUuid, BigDecimal amount, String memo, LocalDateTime now) {
        DirectDebitMandate mandate = mapper.lockMandate(mandateId);
        if (mandate == null || mandate.getFirmId() != firmId) {
            throw new NotFoundException("business.debit.not-found", "id", mandateId);
        }
        if (mandate.getStatus() != DirectDebitStatus.ACTIVE) {
            throw new ConflictException("business.debit.not-active", "id", mandateId);
        }
        if (mandate.getKind() != DirectDebitKind.ON_DEMAND) {
            throw new BadCommandException("business.debit.charge.recurring-only", "id", mandateId);
        }
        LocalDateTime periodStart = mandate.getPeriod().start(now);
        BigDecimal remaining = mandate.getCap().subtract(mapper.committedInPeriod(mandateId, periodStart));
        if (amount.compareTo(remaining) > 0) {
            throw new ExceedsLimitException(
                    "business.debit.charge.cap-exceeded", "remaining", remaining.max(BigDecimal.ZERO));
        }
        DirectDebitCollection collection =
                newCollection(mandateId, periodStart, null, amount, initiatorUuid, memo, now);
        mapper.insertCollection(collection);
        return new Reservation(mandate, collection, remaining.subtract(amount));
    }

    /**
     * Reserves this period's recurring collection if the mandate is active and due.
     * Empty when it isn't due, or this period is already reserved or collected (in
     * which case the mandate is rescheduled for the next period).
     */
    @Transactional
    public Optional<Reservation> reserveRecurring(long mandateId, LocalDateTime now) {
        DirectDebitMandate mandate = mapper.lockMandate(mandateId);
        if (mandate == null
                || mandate.getStatus() != DirectDebitStatus.ACTIVE
                || mandate.getKind() != DirectDebitKind.RECURRING
                || mandate.getNextDueAt() == null
                || mandate.getNextDueAt().isAfter(now)) {
            return Optional.empty();
        }
        LocalDateTime periodStart = mandate.getPeriod().start(now);
        DirectDebitCollection existing = mapper.findRecurringCollection(mandateId, periodStart);
        if (existing == null) {
            DirectDebitCollection collection = newCollection(
                    mandateId, periodStart, periodStart, mandate.getAmount(), mandate.getProposedByUuid(), null, now);
            mapper.insertCollection(collection);
            return Optional.of(new Reservation(mandate, collection));
        }
        if (existing.getStatus() == CollectionStatus.FAILED
                && existing.getAttempts() < MAX_ATTEMPTS_PER_PERIOD
                && mapper.reserveRetry(existing.getCollectionId(), now) == 1) {
            return Optional.of(new Reservation(mandate, mapper.findCollection(existing.getCollectionId())));
        }
        // Already reserved/collected, or out of attempts: nothing more this period.
        mapper.schedule(mandateId, mandate.getPeriod().nextStart(now), mandate.getMissedPeriods());
        return Optional.empty();
    }

    /** Locks and returns the mandate behind a stale reservation, with the collection. */
    @Transactional
    public Optional<Reservation> lockStale(long collectionId) {
        DirectDebitCollection collection = mapper.findCollection(collectionId);
        if (collection == null || collection.getStatus() != CollectionStatus.RESERVED) {
            return Optional.empty();
        }
        DirectDebitMandate mandate = mapper.lockMandate(collection.getMandateId());
        return Optional.of(new Reservation(mandate, collection));
    }

    /** Marks an on-demand collection COLLECTED or FAILED. */
    @Transactional
    public void settleOnDemand(long collectionId, Long txnId, String failureReason, LocalDateTime now) {
        if (txnId != null) {
            mapper.markCollected(collectionId, txnId, now);
        } else {
            mapper.markFailed(collectionId, truncate(failureReason), now);
        }
    }

    /**
     * Settles a recurring collection and reschedules its mandate: the next period
     * after a success; a retry later this period after a failure; the next period
     * (counting a miss) once the period's attempts are exhausted; and suspension
     * after {@value #MAX_MISSED_PERIODS} consecutive missed periods.
     */
    @Transactional
    public RecurringOutcome settleRecurring(
            Reservation reservation, Long txnId, String failureReason, LocalDateTime now) {
        DirectDebitMandate mandate = mapper.lockMandate(reservation.mandate().getMandateId());
        DirectDebitCollection collection = reservation.collection();
        LocalDateTime nextPeriod = mandate.getPeriod().nextStart(collection.getPeriodStart());

        if (txnId != null) {
            mapper.markCollected(collection.getCollectionId(), txnId, now);
            mapper.schedule(mandate.getMandateId(), nextPeriod, 0);
            return RecurringOutcome.COLLECTED;
        }

        mapper.markFailed(collection.getCollectionId(), truncate(failureReason), now);
        if (collection.getAttempts() < MAX_ATTEMPTS_PER_PERIOD) {
            LocalDateTime retryAt = now.plus(RETRY_DELAY);
            mapper.schedule(
                    mandate.getMandateId(),
                    retryAt.isBefore(nextPeriod) ? retryAt : nextPeriod,
                    mandate.getMissedPeriods());
            return RecurringOutcome.RETRY_SCHEDULED;
        }
        int missed = mandate.getMissedPeriods() + 1;
        mapper.schedule(mandate.getMandateId(), nextPeriod, missed);
        if (missed >= MAX_MISSED_PERIODS && mapper.suspend(mandate.getMandateId()) == 1) {
            return RecurringOutcome.SUSPENDED;
        }
        return RecurringOutcome.PERIOD_MISSED;
    }

    private static DirectDebitCollection newCollection(
            long mandateId,
            LocalDateTime periodStart,
            LocalDateTime recurringPeriod,
            BigDecimal amount,
            String initiatorUuid,
            String memo,
            LocalDateTime now) {
        DirectDebitCollection c = new DirectDebitCollection();
        c.setMandateId(mandateId);
        c.setPeriodStart(periodStart);
        c.setRecurringPeriod(recurringPeriod);
        c.setAmount(amount);
        c.setStatus(CollectionStatus.RESERVED);
        c.setAttempts(1);
        c.setInitiatorUuid(initiatorUuid);
        c.setMemo(memo);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) return "Transfer failed";
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }
}
