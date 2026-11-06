package io.paradaux.business.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.paradaux.business.mappers.DirectDebitMapper;
import io.paradaux.business.mappers.FirmAccountsMapper;
import io.paradaux.business.model.directdebit.CollectionStatus;
import io.paradaux.business.model.directdebit.DirectDebitCollection;
import io.paradaux.business.model.directdebit.DirectDebitKind;
import io.paradaux.business.model.directdebit.DirectDebitMandate;
import io.paradaux.business.model.directdebit.DirectDebitPeriod;
import io.paradaux.business.model.directdebit.DirectDebitStatus;
import io.paradaux.business.model.internal.Firm;
import io.paradaux.business.model.internal.FirmPlayer;
import io.paradaux.business.services.DirectDebitService;
import io.paradaux.business.services.FirmNotificationService;
import io.paradaux.business.services.FirmPlayerService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.impl.DirectDebitStore.RecurringOutcome;
import io.paradaux.business.services.impl.DirectDebitStore.Reservation;
import io.paradaux.business.threading.BusinessMessages;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.ExceedsLimitException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.treasury.api.FeatureFlagApi;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.utils.Idempotency;
import java.math.BigDecimal;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DirectDebitServiceImplTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 11, 10, 0); // a Wednesday

    private final DirectDebitMapper mapper = mock(DirectDebitMapper.class);
    private final DirectDebitStore store = mock(DirectDebitStore.class);
    private final FirmAccountsMapper firmAccounts = mock(FirmAccountsMapper.class);
    private final FirmService firms = mock(FirmService.class);
    private final FirmPlayerService players = mock(FirmPlayerService.class);
    private final FirmNotificationService notifications = mock(FirmNotificationService.class);
    private final TreasuryApi treasury = mock(TreasuryApi.class);
    private final FeatureFlagApi flags = mock(FeatureFlagApi.class);
    private final BusinessMessages message = mock(BusinessMessages.class);
    private final Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private final DirectDebitServiceImpl service = new DirectDebitServiceImpl(
            mapper, store, firmAccounts, firms, players, notifications, treasury, flags, message, clock);

    private final UUID payerId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final Firm firm = new Firm();

    @BeforeEach
    void setUp() {
        when(flags.isEnabled(DirectDebitService.FEATURE_FLAG)).thenReturn(true);
        firm.setFirmId(12);
        firm.setDisplayName("Globex");
        firm.setDefaultAccountId(100);
        when(firms.getAnyFirmById(12)).thenReturn(firm);
        when(treasury.formatAmount(any())).thenAnswer(i -> "$" + ((BigDecimal) i.getArgument(0)).toPlainString());
        FirmPlayer p = new FirmPlayer();
        p.setPlayerUuid(payerId.toString());
        p.setCurrentName("Customer");
        when(players.findByUuid(payerId)).thenReturn(Optional.of(p));
    }

    private DirectDebitMandate mandate(DirectDebitKind kind, DirectDebitStatus status) {
        DirectDebitMandate m = new DirectDebitMandate();
        m.setMandateId(7L);
        m.setFirmId(12);
        m.setPayeeAccountId(100);
        m.setPayerAccountId(200);
        m.setPayerUuid(payerId.toString());
        m.setKind(kind);
        m.setPeriod(DirectDebitPeriod.WEEKLY);
        m.setCap(new BigDecimal("1000"));
        m.setAmount(kind == DirectDebitKind.RECURRING ? new BigDecimal("500") : null);
        m.setReference("Rent");
        m.setStatus(status);
        m.setProposedByUuid(actorId.toString());
        m.setMissedPeriods(0);
        return m;
    }

    private Reservation reservation(DirectDebitMandate m, BigDecimal amount, String memo) {
        DirectDebitCollection c = new DirectDebitCollection();
        c.setCollectionId(50L);
        c.setMandateId(m.getMandateId());
        c.setAmount(amount);
        c.setMemo(memo);
        c.setStatus(CollectionStatus.RESERVED);
        c.setInitiatorUuid(actorId.toString());
        c.setPeriodStart(NOW.toLocalDate().atStartOfDay());
        return new Reservation(m, c, new BigDecimal("700"));
    }

    // ---- flag ------------------------------------------------------------------------

    @Test
    void everyEntryPointRejectsWithDisabledWhileTheFlagIsOff() {
        when(flags.isEnabled(DirectDebitService.FEATURE_FLAG)).thenReturn(false);
        assertThat(service.isEnabled()).isFalse();

        List<Runnable> calls = List.of(
                () -> service.propose(
                        12, actorId, payerId, DirectDebitKind.RECURRING, DirectDebitPeriod.DAILY, BigDecimal.TEN, "r"),
                () -> service.accept(7, payerId),
                () -> service.reject(7, payerId),
                () -> service.revoke(7, payerId),
                () -> service.resume(7, payerId),
                () -> service.cancelByFirm(12, 7, actorId),
                () -> service.charge(12, 7, actorId, BigDecimal.TEN, null),
                () -> service.listForPayer(payerId),
                () -> service.listForFirm(12));
        for (Runnable call : calls) {
            assertThatThrownBy(call::run).isInstanceOf(ConflictException.class).hasMessage("business.debit.disabled");
        }
        verifyNoInteractions(mapper, store, notifications, treasury);
    }

    @Test
    void listsDelegateToTheMapper() {
        var forPayer = List.of(mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE));
        when(mapper.listOpenForPayer(payerId.toString())).thenReturn(forPayer);
        when(mapper.listOpenForFirm(12)).thenReturn(List.of());

        assertThat(service.listForPayer(payerId)).isSameAs(forPayer);
        assertThat(service.listForFirm(12)).isEmpty();
    }

    // ---- propose ---------------------------------------------------------------------

    @Nested
    class Propose {
        private final Account payee = Account.builder().accountId(100).build();
        private final Account payerAccount = Account.builder().accountId(200).build();

        @BeforeEach
        void accounts() {
            when(treasury.getAccountById(100)).thenReturn(payee);
            when(treasury.resolveOrCreatePersonal(payerId)).thenReturn(payerAccount);
        }

        private DirectDebitMandate propose(DirectDebitKind kind, BigDecimal limit, String reference) {
            return service.propose(12, actorId, payerId, kind, DirectDebitPeriod.MONTHLY, limit, reference);
        }

        private void assertRejected(Class<? extends RuntimeException> type, String key, Runnable call) {
            assertThatThrownBy(call::run).isInstanceOf(type).hasMessage(key);
            verify(mapper, never()).insertMandate(any());
        }

        @Test
        void recurringInsertsAPendingMandateExpiringInSevenDays() {
            DirectDebitMandate m = propose(DirectDebitKind.RECURRING, new BigDecimal("500"), "  Rent  ");

            ArgumentCaptor<DirectDebitMandate> inserted = ArgumentCaptor.forClass(DirectDebitMandate.class);
            verify(mapper).insertMandate(inserted.capture());
            assertThat(inserted.getValue()).isSameAs(m);
            assertThat(m.getFirmId()).isEqualTo(12);
            assertThat(m.getPayeeAccountId()).isEqualTo(100);
            assertThat(m.getPayerAccountId()).isEqualTo(200);
            assertThat(m.getPayerUuid()).isEqualTo(payerId.toString());
            assertThat(m.getProposedByUuid()).isEqualTo(actorId.toString());
            assertThat(m.getKind()).isEqualTo(DirectDebitKind.RECURRING);
            assertThat(m.getPeriod()).isEqualTo(DirectDebitPeriod.MONTHLY);
            assertThat(m.getCap()).isEqualByComparingTo("500");
            assertThat(m.getAmount()).isEqualByComparingTo("500");
            assertThat(m.getReference()).isEqualTo("Rent");
            assertThat(m.getStatus()).isEqualTo(DirectDebitStatus.PENDING);
            assertThat(m.getProposedAt()).isEqualTo(NOW);
            assertThat(m.getExpiresAt()).isEqualTo(NOW.plusDays(7));
            assertThat(m.getMissedPeriods()).isZero();
            verify(mapper).expireLapsedProposal(12, payerId.toString(), NOW);
        }

        @Test
        void onDemandHasACapAndNoFixedAmount() {
            DirectDebitMandate m = propose(DirectDebitKind.ON_DEMAND, new BigDecimal("900"), "Usage");

            assertThat(m.getCap()).isEqualByComparingTo("900");
            assertThat(m.getAmount()).isNull();
        }

        @Test
        void onlyAnUnexpiredPendingMandateForTheSameFirmBlocks() {
            DirectDebitMandate otherFirm = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.PENDING);
            otherFirm.setFirmId(13);
            otherFirm.setExpiresAt(NOW.plusDays(1));
            DirectDebitMandate active = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE);
            active.setExpiresAt(NOW.plusDays(1));
            DirectDebitMandate lapsed = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.PENDING);
            lapsed.setExpiresAt(NOW.minusMinutes(1));
            when(mapper.listOpenForPayer(payerId.toString())).thenReturn(List.of(otherFirm, active, lapsed));

            propose(DirectDebitKind.RECURRING, BigDecimal.TEN, "Rent");

            verify(mapper).insertMandate(any());
        }

        @Test
        void aLivePendingMandateIsAConflict() {
            DirectDebitMandate pending = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.PENDING);
            pending.setExpiresAt(NOW.plusSeconds(1));
            when(mapper.listOpenForPayer(payerId.toString())).thenReturn(List.of(pending));

            assertRejected(
                    ConflictException.class,
                    "business.debit.propose.pending-exists",
                    () -> propose(DirectDebitKind.RECURRING, BigDecimal.TEN, "Rent"));
            verify(mapper, never()).expireLapsedProposal(anyInt(), anyString(), any());
        }

        @Test
        void nonPositiveOrMissingAmountIsInvalid() {
            for (BigDecimal bad : new BigDecimal[] {null, BigDecimal.ZERO, new BigDecimal("-1")}) {
                assertRejected(
                        BadCommandException.class,
                        "business.finance.invalid-amount",
                        () -> propose(DirectDebitKind.RECURRING, bad, "Rent"));
            }
        }

        @Test
        void blankNullOrOverlongReferenceIsInvalid() {
            for (String bad : new String[] {null, "", "   ", "x".repeat(101)}) {
                assertRejected(
                        BadCommandException.class,
                        "business.debit.propose.invalid-reference",
                        () -> propose(DirectDebitKind.RECURRING, BigDecimal.TEN, bad));
            }
            // exactly the maximum is fine
            propose(DirectDebitKind.RECURRING, BigDecimal.TEN, "x".repeat(100));
            verify(mapper).insertMandate(any());
        }

        @Test
        void missingFirmIsNotFound() {
            when(firms.getFirmById(12)).thenReturn(null);
            // the happy path reads the firm via getFirmById; with it absent the proposal is refused
            assertRejected(
                    NotFoundException.class,
                    "business.general.firm-not-found",
                    () -> propose(DirectDebitKind.RECURRING, BigDecimal.TEN, "Rent"));
        }

        @Test
        void firmWithoutUsablePayeeAccountIsAConflict() {
            when(firms.getFirmById(12)).thenReturn(firm);

            firm.setDefaultAccountId(null);
            assertRejected(
                    ConflictException.class,
                    "business.finance.no-account",
                    () -> propose(DirectDebitKind.RECURRING, BigDecimal.TEN, "Rent"));

            firm.setDefaultAccountId(101);
            when(treasury.getAccountById(101)).thenReturn(null);
            assertRejected(
                    ConflictException.class,
                    "business.finance.no-account",
                    () -> propose(DirectDebitKind.RECURRING, BigDecimal.TEN, "Rent"));

            when(treasury.getAccountById(101))
                    .thenReturn(
                            Account.builder().accountId(101).isArchived(true).build());
            assertRejected(
                    ConflictException.class,
                    "business.finance.no-account",
                    () -> propose(DirectDebitKind.RECURRING, BigDecimal.TEN, "Rent"));
            verify(treasury, never()).resolveOrCreatePersonal(any());
        }

        @Test
        void authorizationGatedPayeeIsRefused() {
            when(firms.getFirmById(12)).thenReturn(firm);
            when(treasury.getAccountById(100))
                    .thenReturn(Account.builder()
                            .accountId(100)
                            .requiresAuthorization(true)
                            .build());

            assertRejected(
                    ConflictException.class,
                    "business.debit.propose.payee-requires-authorization",
                    () -> propose(DirectDebitKind.RECURRING, BigDecimal.TEN, "Rent"));
        }

        @Test
        void payingYourOwnAccountIsRefused() {
            when(firms.getFirmById(12)).thenReturn(firm);
            when(treasury.resolveOrCreatePersonal(payerId))
                    .thenReturn(Account.builder().accountId(100).build());

            assertRejected(
                    BadCommandException.class,
                    "business.debit.propose.same-account",
                    () -> propose(DirectDebitKind.RECURRING, BigDecimal.TEN, "Rent"));
        }

        @Test
        void losingTheRaceOnTheUniqueIndexIsAConflict() {
            when(firms.getFirmById(12)).thenReturn(firm);
            doThrow(new PersistenceException("dup", new SQLIntegrityConstraintViolationException("dup")))
                    .when(mapper)
                    .insertMandate(any());

            assertThatThrownBy(() -> propose(DirectDebitKind.RECURRING, BigDecimal.TEN, "Rent"))
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("business.debit.propose.pending-exists");
        }

        @Test
        void otherPersistenceFailuresPropagateUnchanged() {
            when(firms.getFirmById(12)).thenReturn(firm);
            PersistenceException boom = new PersistenceException("db down", new IllegalStateException("io"));
            doThrow(boom).when(mapper).insertMandate(any());

            assertThatThrownBy(() -> propose(DirectDebitKind.RECURRING, BigDecimal.TEN, "Rent"))
                    .isSameAs(boom);
        }

        @BeforeEach
        void firmLookup() {
            when(firms.getFirmById(12)).thenReturn(firm);
        }
    }

    // ---- accept / reject / revoke / resume / cancelByFirm ------------------------------

    @Test
    void acceptingARecurringMandateMakesItDueNowAndNotifiesTheFirm() {
        DirectDebitMandate m = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.PENDING);
        when(mapper.findMandate(7)).thenReturn(m);
        when(mapper.accept(7, NOW, NOW)).thenReturn(1);

        assertThat(service.accept(7, payerId)).isSameAs(m);

        verify(notifications)
                .notifyFirm(12, "business.debit.notify.accepted", "id", 7L, "payer", "Customer", "firm", "Globex");
    }

    @Test
    void acceptingAnOnDemandMandateSchedulesNothing() {
        DirectDebitMandate m = mandate(DirectDebitKind.ON_DEMAND, DirectDebitStatus.PENDING);
        when(mapper.findMandate(7)).thenReturn(m);
        when(mapper.accept(7, NOW, null)).thenReturn(1);

        service.accept(7, payerId);

        verify(mapper).accept(7, NOW, null);
    }

    @Test
    void acceptingAnExpiredOrAlreadyAnsweredMandateIsAConflict() {
        when(mapper.findMandate(7)).thenReturn(mandate(DirectDebitKind.RECURRING, DirectDebitStatus.PENDING));
        when(mapper.accept(anyLong(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.accept(7, payerId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("business.debit.not-pending");
        verifyNoInteractions(notifications);
    }

    @Test
    void customerActionsRequireAnExistingMandateOwnedByTheCaller() {
        when(mapper.findMandate(7)).thenReturn(null);
        for (Runnable call : List.<Runnable>of(
                () -> service.accept(7, payerId),
                () -> service.reject(7, payerId),
                () -> service.revoke(7, payerId),
                () -> service.resume(7, payerId))) {
            assertThatThrownBy(call::run).isInstanceOf(NotFoundException.class).hasMessage("business.debit.not-found");
        }

        when(mapper.findMandate(7)).thenReturn(mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE));
        UUID stranger = UUID.randomUUID();
        for (Runnable call : List.<Runnable>of(
                () -> service.accept(7, stranger),
                () -> service.reject(7, stranger),
                () -> service.revoke(7, stranger),
                () -> service.resume(7, stranger))) {
            assertThatThrownBy(call::run)
                    .isInstanceOf(NoPermissionException.class)
                    .hasMessage("business.debit.not-yours");
        }
        verify(mapper, never()).accept(anyLong(), any(), any());
        verify(mapper, never()).end(anyLong(), anyString(), any(), anyString(), any());
        verify(mapper, never()).resume(anyLong(), any());
    }

    @Test
    void payerMatchIgnoresUuidCase() {
        DirectDebitMandate m = mandate(DirectDebitKind.ON_DEMAND, DirectDebitStatus.SUSPENDED);
        m.setPayerUuid(payerId.toString().toUpperCase(java.util.Locale.ROOT));
        when(mapper.findMandate(7)).thenReturn(m);
        when(mapper.resume(7, NOW)).thenReturn(1);

        assertThat(service.resume(7, payerId)).isSameAs(m);
    }

    @Test
    void rejectEndsAPendingMandateAsRejected() {
        DirectDebitMandate m = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.PENDING);
        when(mapper.findMandate(7)).thenReturn(m);
        when(mapper.end(7, "REJECTED", payerId.toString(), "Rejected by customer", NOW))
                .thenReturn(1);

        assertThat(service.reject(7, payerId)).isSameAs(m);
    }

    @Test
    void rejectOnANonPendingMandateConflictsWithoutTouchingIt() {
        when(mapper.findMandate(7)).thenReturn(mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE));

        assertThatThrownBy(() -> service.reject(7, payerId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("business.debit.not-pending");
        verify(mapper, never()).end(anyLong(), anyString(), any(), anyString(), any());
    }

    @Test
    void rejectLosingTheRaceConflicts() {
        when(mapper.findMandate(7)).thenReturn(mandate(DirectDebitKind.RECURRING, DirectDebitStatus.PENDING));
        when(mapper.end(anyLong(), anyString(), any(), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.reject(7, payerId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("business.debit.not-pending");
    }

    @Test
    void revokeCancelsAndNotifiesTheFirm() {
        DirectDebitMandate m = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE);
        when(mapper.findMandate(7)).thenReturn(m);
        when(mapper.end(7, "CANCELLED", payerId.toString(), "Cancelled by customer", NOW))
                .thenReturn(1);

        assertThat(service.revoke(7, payerId)).isSameAs(m);

        verify(notifications)
                .notifyFirm(12, "business.debit.notify.revoked", "id", 7L, "payer", "Customer", "firm", "Globex");
    }

    @Test
    void revokeOfAnEndedMandateConflicts() {
        when(mapper.findMandate(7)).thenReturn(mandate(DirectDebitKind.RECURRING, DirectDebitStatus.CANCELLED));
        when(mapper.end(anyLong(), anyString(), any(), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.revoke(7, payerId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("business.debit.already-ended");
        verifyNoInteractions(notifications);
    }

    @Test
    void resumeOfAMandateThatIsNotSuspendedConflicts() {
        when(mapper.findMandate(7)).thenReturn(mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE));
        when(mapper.resume(7, NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.resume(7, payerId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("business.debit.not-suspended");
    }

    @Test
    void cancelByFirmEndsTheMandateAndTellsThePayer() {
        DirectDebitMandate m = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE);
        when(mapper.findMandate(7)).thenReturn(m);
        when(mapper.end(7, "CANCELLED", actorId.toString(), "Cancelled by firm", NOW))
                .thenReturn(1);

        assertThat(service.cancelByFirm(12, 7, actorId)).isSameAs(m);

        verify(message).send(payerId, "business.debit.notify.cancelled-by-firm", "id", 7L, "firm", "Globex");
    }

    @Test
    void cancelByFirmHidesOtherFirmsMandatesAndMissingOnes() {
        when(mapper.findMandate(7)).thenReturn(mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE));
        assertThatThrownBy(() -> service.cancelByFirm(13, 7, actorId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("business.debit.not-found");

        when(mapper.findMandate(8)).thenReturn(null);
        assertThatThrownBy(() -> service.cancelByFirm(12, 8, actorId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("business.debit.not-found");
        verify(mapper, never()).end(anyLong(), anyString(), any(), anyString(), any());
    }

    @Test
    void cancelByFirmOfAnEndedMandateConflictsAndStaysQuiet() {
        when(mapper.findMandate(7)).thenReturn(mandate(DirectDebitKind.RECURRING, DirectDebitStatus.CANCELLED));
        when(mapper.end(anyLong(), anyString(), any(), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.cancelByFirm(12, 7, actorId))
                .isInstanceOf(ConflictException.class)
                .hasMessage("business.debit.already-ended");
        verifyNoInteractions(message);
    }

    @Test
    void notificationsFallBackToIdsWhenTheFirmOrPlayerCannotBeResolved() {
        when(firms.getAnyFirmById(12)).thenReturn(null);
        when(players.findByUuid(payerId)).thenReturn(Optional.empty());
        DirectDebitMandate m = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE);
        when(mapper.findMandate(7)).thenReturn(m);
        when(mapper.end(anyLong(), anyString(), any(), anyString(), any())).thenReturn(1);

        service.revoke(7, payerId);

        verify(notifications)
                .notifyFirm(12, "business.debit.notify.revoked", "id", 7L, "payer", payerId.toString(), "firm", "#12");
    }

    // ---- charge ----------------------------------------------------------------------

    @Nested
    class Charge {
        private static final BigDecimal TEN2 = new BigDecimal("10.00");

        private final DirectDebitMandate m = mandate(DirectDebitKind.ON_DEMAND, DirectDebitStatus.ACTIVE);

        private Reservation reserved(String memo) {
            Reservation r = reservation(m, TEN2, memo);
            when(store.reserveOnDemand(eq(12), eq(7L), eq(actorId.toString()), eq(TEN2), any(), eq(NOW)))
                    .thenReturn(r);
            when(firms.getFirmById(12)).thenReturn(firm);
            when(firmAccounts.isFirmAccount(12, 100)).thenReturn(true);
            return r;
        }

        @Test
        void successTransfersWithAStableIdempotencyKeySettlesAndReportsRemaining() {
            reserved(null);
            when(treasury.hasFunds(200, TEN2)).thenReturn(true);
            when(treasury.transfer(any())).thenReturn(99L);

            DirectDebitService.Charge charge = service.charge(12, 7, actorId, TEN2, null);

            assertThat(charge.collectionId()).isEqualTo(50L);
            assertThat(charge.txnId()).isEqualTo(99L);
            assertThat(charge.amount()).isEqualByComparingTo("10");
            assertThat(charge.remainingThisPeriod()).isEqualByComparingTo("700");
            verify(store).settleOnDemand(50L, 99L, null, NOW);

            ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
            verify(treasury).transfer(req.capture());
            TransferRequest sent = req.getValue();
            assertThat(sent.fromAccountId()).isEqualTo(200);
            assertThat(sent.toAccountId()).isEqualTo(100);
            assertThat(sent.amount()).isEqualByComparingTo("10");
            assertThat(sent.message()).isEqualTo("Direct debit: Globex — Rent #7");
            assertThat(sent.initiator()).isEqualTo(actorId);
            assertThat(sent.authorizer()).isEqualTo(payerId);
            assertThat(sent.pluginSystem()).isEqualTo(DirectDebitService.PLUGIN_SYSTEM);
            assertThat(sent.dedupKey()).isEqualTo(Idempotency.sha256(DirectDebitService.PLUGIN_SYSTEM + ":50"));
            verify(message)
                    .send(
                            payerId,
                            "business.debit.notify.collected",
                            "id",
                            7L,
                            "firm",
                            "Globex",
                            "amount",
                            "$10.00",
                            "reference",
                            "Rent");
        }

        @Test
        void aSettlementErrorAfterTheMoneyMoved_stillReportsTheCharge() {
            reserved(null);
            when(treasury.hasFunds(200, TEN2)).thenReturn(true);
            when(treasury.transfer(any())).thenReturn(99L);
            doThrow(new IllegalStateException("db down")).when(store).settleOnDemand(anyLong(), any(), any(), any());

            DirectDebitService.Charge charge = service.charge(12, 7, actorId, TEN2, null);

            // The job settles the stale reservation later; the firm sees the real outcome.
            assertThat(charge.txnId()).isEqualTo(99L);
            verify(mapper, never()).committedInPeriod(anyLong(), any());
        }

        @Test
        void memoIsTrimmedForwardedAndShownInTheLedgerMessage() {
            reserved("March");
            when(treasury.hasFunds(200, TEN2)).thenReturn(true);
            when(treasury.transfer(any())).thenReturn(99L);

            service.charge(12, 7, actorId, TEN2, "  March ");

            verify(store).reserveOnDemand(12, 7L, actorId.toString(), TEN2, "March", NOW);
            ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
            verify(treasury).transfer(req.capture());
            assertThat(req.getValue().message()).isEqualTo("Direct debit: Globex — Rent (March) #7");
        }

        @Test
        void aBlankMemoIsTreatedAsNone() {
            reserved(null);
            when(treasury.hasFunds(200, TEN2)).thenReturn(true);
            when(treasury.transfer(any())).thenReturn(99L);

            service.charge(12, 7, actorId, TEN2, "   ");

            verify(store).reserveOnDemand(12, 7L, actorId.toString(), TEN2, null, NOW);
        }

        @Test
        void ledgerMessageIsCappedAt255Characters() {
            reserved(null);
            firm.setDisplayName("G".repeat(250));
            when(treasury.hasFunds(200, TEN2)).thenReturn(true);
            when(treasury.transfer(any())).thenReturn(99L);

            service.charge(12, 7, actorId, TEN2, null);

            ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
            verify(treasury).transfer(req.capture());
            assertThat(req.getValue().message()).hasSize(255).startsWith("Direct debit: GGG");
        }

        @Test
        void invalidAmountAndOverlongMemoAreRejectedBeforeReserving() {
            assertThatThrownBy(() -> service.charge(12, 7, actorId, BigDecimal.ZERO, null))
                    .isInstanceOf(BadCommandException.class)
                    .hasMessage("business.finance.invalid-amount");
            assertThatThrownBy(() -> service.charge(12, 7, actorId, TEN2, "m".repeat(101)))
                    .isInstanceOf(BadCommandException.class)
                    .hasMessage("business.debit.charge.memo-too-long");
            verifyNoInteractions(store, treasury);
        }

        @Test
        void insufficientFundsIsRecordedFailedTheCustomerToldAndTheChargeRefused() {
            reserved(null);
            when(treasury.hasFunds(200, TEN2)).thenReturn(false);

            assertThatThrownBy(() -> service.charge(12, 7, actorId, TEN2, null))
                    .isInstanceOf(ExceedsLimitException.class)
                    .hasMessage("business.debit.charge.failed");

            verify(treasury, never()).transfer(any());
            verify(store).settleOnDemand(50L, null, "Insufficient funds", NOW);
            verify(message)
                    .send(payerId, "business.debit.notify.failed", "id", 7L, "firm", "Globex", "amount", "$10.00");
            verify(mapper, never()).committedInPeriod(anyLong(), any());
        }

        @Test
        void aThrowingTransferIsRecordedWithItsReason() {
            reserved(null);
            when(treasury.hasFunds(200, TEN2)).thenReturn(true);
            when(treasury.transfer(any())).thenThrow(new IllegalStateException("ledger offline"));

            assertThatThrownBy(() -> service.charge(12, 7, actorId, TEN2, null))
                    .isInstanceOf(ExceedsLimitException.class)
                    .hasMessage("business.debit.charge.failed");

            verify(store).settleOnDemand(50L, null, "ledger offline", NOW);
            verify(message)
                    .send(eq(payerId), eq("business.debit.notify.failed"), any(), any(), any(), any(), any(), any());
        }

        @Test
        void aDisbandedFirmEndsTheMandateAndFailsTheCharge() {
            reserved(null);
            when(firms.getFirmById(12)).thenReturn(null);

            assertThatThrownBy(() -> service.charge(12, 7, actorId, TEN2, null))
                    .isInstanceOf(ExceedsLimitException.class)
                    .hasMessage("business.debit.charge.failed");

            verify(mapper).end(7L, "CANCELLED", null, "Firm or receiving account no longer available", NOW);
            verify(store).settleOnDemand(50L, null, "The firm or its receiving account is no longer available", NOW);
            verify(treasury, never()).hasFunds(anyInt(), any());
            verify(treasury, never()).transfer(any());
        }

        @Test
        void aPayeeAccountTheFirmNoLongerOwnsAlsoEndsTheMandate() {
            reserved(null);
            when(firmAccounts.isFirmAccount(12, 100)).thenReturn(false);

            assertThatThrownBy(() -> service.charge(12, 7, actorId, TEN2, null))
                    .isInstanceOf(ExceedsLimitException.class);

            verify(mapper).end(eq(7L), eq("CANCELLED"), isNull(), anyString(), eq(NOW));
            verify(treasury, never()).transfer(any());
        }
    }

    // ---- job -------------------------------------------------------------------------

    @Nested
    class Job {
        @Test
        void isANoOpWhileDisabled() {
            when(flags.isEnabled(DirectDebitService.FEATURE_FLAG)).thenReturn(false);

            DirectDebitService.JobResult result = service.runJob();

            assertThat(result).isEqualTo(new DirectDebitService.JobResult(0, 0, 0, 0));
            assertThat(result.isEmpty()).isTrue();
            verifyNoInteractions(mapper, store, treasury);
        }

        @Test
        void reportsExpiredProposalsAndIsNotEmptyBecauseOfThem() {
            when(mapper.expireLapsedProposals(NOW)).thenReturn(2);

            DirectDebitService.JobResult result = service.runJob();

            assertThat(result).isEqualTo(new DirectDebitService.JobResult(2, 0, 0, 0));
            assertThat(result.isEmpty()).isFalse();
        }

        private void transferWorks() {
            when(firms.getFirmById(12)).thenReturn(firm);
            when(firmAccounts.isFirmAccount(12, 100)).thenReturn(true);
            when(treasury.hasFunds(eq(200), any())).thenReturn(true);
            when(treasury.transfer(any())).thenReturn(99L);
        }

        private void transferFailsForFunds() {
            when(firms.getFirmById(12)).thenReturn(firm);
            when(firmAccounts.isFirmAccount(12, 100)).thenReturn(true);
            when(treasury.hasFunds(eq(200), any())).thenReturn(false);
        }

        private Reservation dueRecurring(RecurringOutcome outcome) {
            DirectDebitMandate m = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE);
            Reservation r = reservation(m, new BigDecimal("500"), null);
            when(mapper.dueRecurring(NOW, DirectDebitServiceImpl.JOB_BATCH)).thenReturn(List.of(7L));
            when(store.reserveRecurring(7L, NOW)).thenReturn(Optional.of(r));
            when(store.settleRecurring(eq(r), any(), any(), eq(NOW))).thenReturn(outcome);
            return r;
        }

        private static final String FAILED = "business.debit.notify.failed";

        @Test
        void aCollectedPeriodNotifiesOnlyTheCustomerOfTheCollection() {
            Reservation r = dueRecurring(RecurringOutcome.COLLECTED);
            transferWorks();

            assertThat(service.runJob()).isEqualTo(new DirectDebitService.JobResult(0, 0, 1, 0));

            verify(store).settleRecurring(r, 99L, null, NOW);
            verify(message)
                    .send(
                            payerId,
                            "business.debit.notify.collected",
                            "id",
                            7L,
                            "firm",
                            "Globex",
                            "amount",
                            "$500",
                            "reference",
                            "Rent");
            verifyNoInteractions(notifications);
            // recurring collections are initiated by whoever proposed the mandate
            ArgumentCaptor<TransferRequest> req = ArgumentCaptor.forClass(TransferRequest.class);
            verify(treasury).transfer(req.capture());
            assertThat(req.getValue().initiator()).isEqualTo(actorId);
        }

        @Test
        void aRetryIsOnlyToldToTheCustomer() {
            Reservation r = dueRecurring(RecurringOutcome.RETRY_SCHEDULED);
            transferFailsForFunds();

            assertThat(service.runJob()).isEqualTo(new DirectDebitService.JobResult(0, 0, 0, 1));

            verify(store).settleRecurring(r, null, "Insufficient funds", NOW);
            verify(message).send(payerId, FAILED, "id", 7L, "firm", "Globex", "amount", "$500");
            verifyNoMoreInteractions(message);
            verifyNoInteractions(notifications);
        }

        @Test
        void aMissedPeriodAlsoTellsTheFirmButDoesNotSayItIsSuspended() {
            dueRecurring(RecurringOutcome.PERIOD_MISSED);
            transferFailsForFunds();

            assertThat(service.runJob()).isEqualTo(new DirectDebitService.JobResult(0, 0, 0, 1));

            verify(message).send(payerId, FAILED, "id", 7L, "firm", "Globex", "amount", "$500");
            verifyNoMoreInteractions(message);
            verify(notifications)
                    .notifyFirm(
                            12,
                            "business.debit.notify.firm-missed",
                            "id",
                            7L,
                            "payer",
                            "Customer",
                            "firm",
                            "Globex",
                            "amount",
                            "$500");
        }

        @Test
        void suspensionTellsTheCustomerTwiceAndTheFirm() {
            dueRecurring(RecurringOutcome.SUSPENDED);
            transferFailsForFunds();

            assertThat(service.runJob()).isEqualTo(new DirectDebitService.JobResult(0, 0, 0, 1));

            verify(message).send(payerId, FAILED, "id", 7L, "firm", "Globex", "amount", "$500");
            verify(message).send(payerId, "business.debit.notify.suspended", "id", 7L, "firm", "Globex");
            verify(notifications)
                    .notifyFirm(
                            12,
                            "business.debit.notify.firm-suspended",
                            "id",
                            7L,
                            "payer",
                            "Customer",
                            "firm",
                            "Globex",
                            "amount",
                            "$500");
        }

        @Test
        void nothingToAttemptAndFailuresInOneMandateDoNotStopTheRest() {
            DirectDebitMandate ok = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE);
            Reservation good = reservation(ok, new BigDecimal("500"), null);
            when(mapper.dueRecurring(NOW, DirectDebitServiceImpl.JOB_BATCH)).thenReturn(List.of(1L, 2L, 7L));
            when(store.reserveRecurring(1L, NOW)).thenReturn(Optional.empty());
            when(store.reserveRecurring(2L, NOW)).thenThrow(new IllegalStateException("deadlock"));
            when(store.reserveRecurring(7L, NOW)).thenReturn(Optional.of(good));
            when(store.settleRecurring(eq(good), any(), any(), eq(NOW))).thenReturn(RecurringOutcome.COLLECTED);
            transferWorks();

            assertThat(service.runJob()).isEqualTo(new DirectDebitService.JobResult(0, 0, 1, 0));
        }

        @Test
        void staleReservationsAreSettledByKindAndOnlyCompletedOnesAreCounted() {
            DirectDebitMandate recurring = mandate(DirectDebitKind.RECURRING, DirectDebitStatus.ACTIVE);
            Reservation recurringRes = reservation(recurring, new BigDecimal("500"), null);
            DirectDebitMandate onDemand = mandate(DirectDebitKind.ON_DEMAND, DirectDebitStatus.CANCELLED);
            Reservation onDemandRes = reservation(onDemand, BigDecimal.TEN, "memo");
            onDemandRes.collection().setCollectionId(51L);
            when(mapper.staleReservations(NOW.minus(DirectDebitServiceImpl.STALE_RESERVATION), 100))
                    .thenReturn(List.of(1L, 2L, 3L, 4L));
            when(store.lockStale(1L)).thenReturn(Optional.of(recurringRes));
            when(store.lockStale(2L)).thenReturn(Optional.empty());
            when(store.lockStale(3L)).thenThrow(new IllegalStateException("lock timeout"));
            when(store.lockStale(4L)).thenReturn(Optional.of(onDemandRes));
            when(treasury.hasFunds(eq(200), any())).thenReturn(true);
            when(treasury.transfer(any())).thenReturn(77L);
            when(store.settleRecurring(recurringRes, 77L, null, NOW))
                    .thenReturn(DirectDebitStore.RecurringOutcome.COLLECTED);

            assertThat(service.runJob()).isEqualTo(new DirectDebitService.JobResult(0, 2, 0, 0));

            verify(store).settleRecurring(recurringRes, 77L, null, NOW);
            verify(store).settleOnDemand(4L, 77L, null, NOW);
            // recovery replays the transfer without re-checking the firm: it was admitted already
            verify(firms, never()).getFirmById(anyInt());
            verify(firmAccounts, never()).isFirmAccount(any(), any());
            verify(mapper, never()).end(anyLong(), anyString(), any(), anyString(), any());
            // Recovered collections are reported to the customer like any other.
            verify(message, times(2)).send(eq(payerId), eq("business.debit.notify.collected"), any(Object[].class));
            verifyNoInteractions(notifications);
        }

        @Test
        void aRecoveredTransferThatFailsIsRecordedFailed() {
            DirectDebitMandate onDemand = mandate(DirectDebitKind.ON_DEMAND, DirectDebitStatus.ACTIVE);
            Reservation r = reservation(onDemand, BigDecimal.TEN, null);
            when(mapper.staleReservations(any(), anyInt())).thenReturn(List.of(50L));
            when(store.lockStale(50L)).thenReturn(Optional.of(r));
            when(treasury.hasFunds(eq(200), any())).thenReturn(false);

            assertThat(service.runJob()).isEqualTo(new DirectDebitService.JobResult(0, 1, 0, 0));

            verify(store).settleOnDemand(50L, null, "Insufficient funds", NOW);
            verify(message).send(eq(payerId), eq("business.debit.notify.failed"), any(Object[].class));
        }
    }
}
