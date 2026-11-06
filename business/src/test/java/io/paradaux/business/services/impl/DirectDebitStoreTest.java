package io.paradaux.business.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paradaux.business.internal.model.directdebit.CollectionStatus;
import io.paradaux.business.internal.model.directdebit.DirectDebitCollection;
import io.paradaux.business.internal.model.directdebit.DirectDebitKind;
import io.paradaux.business.internal.model.directdebit.DirectDebitMandate;
import io.paradaux.business.internal.model.directdebit.DirectDebitPeriod;
import io.paradaux.business.internal.model.directdebit.DirectDebitStatus;
import io.paradaux.business.mappers.DirectDebitMapper;
import io.paradaux.business.services.impl.DirectDebitStore.RecurringOutcome;
import io.paradaux.business.services.impl.DirectDebitStore.Reservation;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.ExceedsLimitException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The cap, once-per-period and retry/suspension rules of direct-debit collections
 * (PAR-557), against a mocked mapper. The real-database behaviour (row locking,
 * unique keys) is covered by {@code DirectDebitRealTreasuryIT}.
 */
class DirectDebitStoreTest {

    private static final LocalDateTime NOW = LocalDateTime.parse("2026-11-04T10:00:00"); // a Wednesday
    private static final LocalDateTime DAY = NOW.toLocalDate().atStartOfDay();
    private static final LocalDateTime NEXT_DAY = DAY.plusDays(1);
    private static final String ACTOR = "00000000-0000-0000-0000-00000000000a";

    private final DirectDebitMapper mapper = mock(DirectDebitMapper.class);
    private final DirectDebitStore store = new DirectDebitStore(mapper);

    private static DirectDebitMandate mandate(DirectDebitKind kind, DirectDebitStatus status) {
        DirectDebitMandate m = new DirectDebitMandate();
        m.setMandateId(7L);
        m.setFirmId(12);
        m.setKind(kind);
        m.setStatus(status);
        m.setPeriod(DirectDebitPeriod.DAILY);
        m.setCap(new BigDecimal("100.00"));
        m.setAmount(kind == DirectDebitKind.RECURRING ? new BigDecimal("25.00") : null);
        m.setProposedByUuid(ACTOR);
        m.setMissedPeriods(0);
        m.setNextDueAt(kind == DirectDebitKind.RECURRING ? NOW : null);
        return m;
    }

    private static DirectDebitCollection collection(CollectionStatus status, int attempts) {
        DirectDebitCollection c = new DirectDebitCollection();
        c.setCollectionId(50L);
        c.setMandateId(7L);
        c.setPeriodStart(DAY);
        c.setRecurringPeriod(DAY);
        c.setAmount(new BigDecimal("25.00"));
        c.setStatus(status);
        c.setAttempts(attempts);
        return c;
    }

    @Nested
    class OnDemand {

        @Test
        void reservesWithinTheCap_andReportsWhatIsLeft() {
            when(mapper.lockMandate(7L)).thenReturn(mandate(DirectDebitKind.ON_DEMAND, DirectDebitStatus.ACTIVE));
            when(mapper.committedInPeriod(7L, DAY)).thenReturn(new BigDecimal("60.00"));

            Reservation r = store.reserveOnDemand(12, 7L, ACTOR, new BigDecimal("40.00"), "memo", NOW);

            assertThat(r.remaining()).isEqualByComparingTo("0");
            ArgumentCaptor<DirectDebitCollection> inserted = ArgumentCaptor.forClass(DirectDebitCollection.class);
            verify(mapper).insertCollection(inserted.capture());
            DirectDebitCollection c = inserted.getValue();
            assertThat(c.getMandateId()).isEqualTo(7L);
            assertThat(c.getPeriodStart()).isEqualTo(DAY);
            assertThat(c.getRecurringPeriod()).isNull();
            assertThat(c.getAmount()).isEqualByComparingTo("40.00");
            assertThat(c.getStatus()).isEqualTo(CollectionStatus.RESERVED);
            assertThat(c.getAttempts()).isEqualTo(1);
            assertThat(c.getInitiatorUuid()).isEqualTo(ACTOR);
            assertThat(c.getMemo()).isEqualTo("memo");
            assertThat(c.getCreatedAt()).isEqualTo(NOW);
            assertThat(c.getUpdatedAt()).isEqualTo(NOW);
            assertThat(r.collection()).isSameAs(c);
        }

        @Test
        void refusesOneCentOverTheCap_reportingTheRemainder() {
            when(mapper.lockMandate(7L)).thenReturn(mandate(DirectDebitKind.ON_DEMAND, DirectDebitStatus.ACTIVE));
            when(mapper.committedInPeriod(7L, DAY)).thenReturn(new BigDecimal("60.00"));

            assertThatThrownBy(() -> store.reserveOnDemand(12, 7L, ACTOR, new BigDecimal("40.01"), null, NOW))
                    .isInstanceOf(ExceedsLimitException.class)
                    .hasMessage("business.debit.charge.cap-exceeded");
            verify(mapper, never()).insertCollection(any());
        }

        @Test
        void unknownOrForeignMandatesAreNotFound() {
            assertThatThrownBy(() -> store.reserveOnDemand(12, 7L, ACTOR, BigDecimal.ONE, null, NOW))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("business.debit.not-found");

            when(mapper.lockMandate(7L)).thenReturn(mandate(DirectDebitKind.ON_DEMAND, DirectDebitStatus.ACTIVE));
            assertThatThrownBy(() -> store.reserveOnDemand(13, 7L, ACTOR, BigDecimal.ONE, null, NOW))
                    .isInstanceOf(NotFoundException.class);
            verify(mapper, never()).insertCollection(any());
        }

        @Test
        void inactiveMandatesAreRefused() {
            when(mapper.lockMandate(7L)).thenReturn(mandate(DirectDebitKind.ON_DEMAND, DirectDebitStatus.SUSPENDED));

            assertThatThrownBy(() -> store.reserveOnDemand(12, 7L, ACTOR, BigDecimal.ONE, null, NOW))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("business.debit.not-active");
        }

        @Test
        void recurringMandatesCannotBeChargedByHand() {
            when(mapper.lockMandate(7L)).thenReturn(mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE));

            assertThatThrownBy(() -> store.reserveOnDemand(12, 7L, ACTOR, BigDecimal.ONE, null, NOW))
                    .isInstanceOf(BadCommandException.class)
                    .hasMessage("business.debit.charge.recurring-only");
            verify(mapper, never()).committedInPeriod(anyLong(), any());
        }

        @Test
        void settlingRecordsTheTxnOrTheReason() {
            store.settleOnDemand(50L, 99L, null, NOW);
            verify(mapper).markCollected(50L, 99L, NOW);

            store.settleOnDemand(51L, null, "Insufficient funds", NOW);
            verify(mapper).markFailed(51L, "Insufficient funds", NOW);

            store.settleOnDemand(52L, null, "  ", NOW);
            verify(mapper).markFailed(52L, "Transfer failed", NOW);

            store.settleOnDemand(53L, null, "x".repeat(300), NOW);
            verify(mapper).markFailed(53L, "x".repeat(255), NOW);
        }
    }

    @Nested
    class ReserveRecurring {

        @Test
        void aDueMandateReservesThisPeriod() {
            when(mapper.lockMandate(7L)).thenReturn(mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE));

            Reservation r = store.reserveRecurring(7L, NOW).orElseThrow();

            assertThat(r.collection().getPeriodStart()).isEqualTo(DAY);
            assertThat(r.collection().getRecurringPeriod()).isEqualTo(DAY);
            assertThat(r.collection().getAmount()).isEqualByComparingTo("25.00");
            assertThat(r.collection().getInitiatorUuid()).isEqualTo(ACTOR);
            assertThat(r.remaining()).isNull();
            verify(mapper).insertCollection(r.collection());
        }

        @Test
        void nothingIsReservedUnlessActiveRecurringAndDue() {
            assertThat(store.reserveRecurring(7L, NOW)).isEmpty(); // unknown

            DirectDebitMandate cancelled = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.CANCELLED);
            DirectDebitMandate onDemand = mandate(DirectDebitKind.ON_DEMAND, DirectDebitStatus.ACTIVE);
            DirectDebitMandate unscheduled = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE);
            unscheduled.setNextDueAt(null);
            DirectDebitMandate notYet = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE);
            notYet.setNextDueAt(NOW.plusSeconds(1));
            for (DirectDebitMandate m : new DirectDebitMandate[] {cancelled, onDemand, unscheduled, notYet}) {
                when(mapper.lockMandate(7L)).thenReturn(m);
                assertThat(store.reserveRecurring(7L, NOW)).isEmpty();
            }
            verify(mapper, never()).insertCollection(any());
            verify(mapper, never()).schedule(anyLong(), any(), anyInt());
        }

        @Test
        void aFailedPeriodWithAttemptsLeftIsRetried() {
            when(mapper.lockMandate(7L)).thenReturn(mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE));
            when(mapper.findRecurringCollection(7L, DAY)).thenReturn(collection(CollectionStatus.FAILED, 2));
            when(mapper.reserveRetry(50L, NOW)).thenReturn(1);
            DirectDebitCollection retried = collection(CollectionStatus.RESERVED, 3);
            when(mapper.findCollection(50L)).thenReturn(retried);

            assertThat(store.reserveRecurring(7L, NOW))
                    .map(Reservation::collection)
                    .contains(retried);
            verify(mapper, never()).insertCollection(any());
        }

        @Test
        void aPeriodAlreadyHandledReschedulesForTheNextOne() {
            DirectDebitMandate m = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE);
            m.setMissedPeriods(2);
            when(mapper.lockMandate(7L)).thenReturn(m);
            for (DirectDebitCollection existing : new DirectDebitCollection[] {
                collection(CollectionStatus.COLLECTED, 1),
                collection(CollectionStatus.RESERVED, 1),
                collection(CollectionStatus.FAILED, DirectDebitStore.MAX_ATTEMPTS_PER_PERIOD),
            }) {
                when(mapper.findRecurringCollection(7L, DAY)).thenReturn(existing);
                assertThat(store.reserveRecurring(7L, NOW)).isEmpty();
            }
            verify(mapper, org.mockito.Mockito.times(3)).schedule(7L, NEXT_DAY, 2);
            verify(mapper, never()).reserveRetry(anyLong(), any());
        }

        @Test
        void aRetryLostToAConcurrentRunReschedules() {
            when(mapper.lockMandate(7L)).thenReturn(mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE));
            when(mapper.findRecurringCollection(7L, DAY)).thenReturn(collection(CollectionStatus.FAILED, 1));
            when(mapper.reserveRetry(50L, NOW)).thenReturn(0);

            assertThat(store.reserveRecurring(7L, NOW)).isEmpty();
            verify(mapper).schedule(7L, NEXT_DAY, 0);
        }
    }

    @Nested
    class SettleRecurring {

        private Reservation reserved(int attempts, int missed) {
            DirectDebitMandate m = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE);
            m.setMissedPeriods(missed);
            when(mapper.lockMandate(7L)).thenReturn(m);
            return new Reservation(m, collection(CollectionStatus.RESERVED, attempts));
        }

        @Test
        void successSchedulesTheNextPeriodAndClearsMisses() {
            Reservation r = reserved(2, 2);

            assertThat(store.settleRecurring(r, 99L, null, NOW)).isEqualTo(RecurringOutcome.COLLECTED);
            verify(mapper).markCollected(50L, 99L, NOW);
            verify(mapper).schedule(7L, NEXT_DAY, 0);
        }

        @Test
        void aFailureWithAttemptsLeftRetriesInAnHour() {
            Reservation r = reserved(1, 1);

            assertThat(store.settleRecurring(r, null, "Insufficient funds", NOW))
                    .isEqualTo(RecurringOutcome.RETRY_SCHEDULED);
            verify(mapper).markFailed(50L, "Insufficient funds", NOW);
            verify(mapper).schedule(7L, NOW.plus(DirectDebitStore.RETRY_DELAY), 1);
        }

        @Test
        void aRetryNeverSpillsIntoTheNextPeriod() {
            Reservation r = reserved(1, 0);
            LocalDateTime lateEvening = DAY.plusHours(23).plusMinutes(30);

            store.settleRecurring(r, null, "Insufficient funds", lateEvening);
            verify(mapper).schedule(7L, NEXT_DAY, 0);
        }

        @Test
        void theLastFailedAttemptMissesThePeriod() {
            Reservation r = reserved(DirectDebitStore.MAX_ATTEMPTS_PER_PERIOD, 0);

            assertThat(store.settleRecurring(r, null, "Insufficient funds", NOW))
                    .isEqualTo(RecurringOutcome.PERIOD_MISSED);
            verify(mapper).schedule(7L, NEXT_DAY, 1);
            verify(mapper, never()).suspend(anyLong());
        }

        @Test
        void theThirdMissedPeriodSuspends() {
            Reservation r = reserved(DirectDebitStore.MAX_ATTEMPTS_PER_PERIOD, DirectDebitStore.MAX_MISSED_PERIODS - 1);
            when(mapper.suspend(7L)).thenReturn(1);

            assertThat(store.settleRecurring(r, null, "Insufficient funds", NOW))
                    .isEqualTo(RecurringOutcome.SUSPENDED);
            verify(mapper).schedule(7L, NEXT_DAY, DirectDebitStore.MAX_MISSED_PERIODS);
        }

        @Test
        void aMandateEndedMeanwhileIsNotReportedSuspended() {
            Reservation r = reserved(DirectDebitStore.MAX_ATTEMPTS_PER_PERIOD, DirectDebitStore.MAX_MISSED_PERIODS - 1);
            when(mapper.suspend(7L)).thenReturn(0);

            assertThat(store.settleRecurring(r, null, "Insufficient funds", NOW))
                    .isEqualTo(RecurringOutcome.PERIOD_MISSED);
        }
    }

    @Nested
    class Recovery {

        @Test
        void locksTheMandateOfAStillReservedCollection() {
            DirectDebitCollection c = collection(CollectionStatus.RESERVED, 1);
            DirectDebitMandate m = mandate(DirectDebitKind.ON_DEMAND, DirectDebitStatus.ACTIVE);
            when(mapper.findCollection(50L)).thenReturn(c);
            when(mapper.lockMandate(7L)).thenReturn(m);

            Reservation r = store.lockStale(50L).orElseThrow();
            assertThat(r.mandate()).isSameAs(m);
            assertThat(r.collection()).isSameAs(c);
        }

        @Test
        void skipsCollectionsAlreadySettledOrGone() {
            assertThat(store.lockStale(50L)).isEmpty();
            when(mapper.findCollection(50L)).thenReturn(collection(CollectionStatus.COLLECTED, 1));
            assertThat(store.lockStale(50L)).isEqualTo(Optional.empty());
            verify(mapper, never()).lockMandate(anyLong());
        }
    }
}
