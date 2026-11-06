package io.paradaux.business.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.mappers.DirectDebitMapper;
import io.paradaux.business.mappers.FirmAccountsMapper;
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
import io.paradaux.common.AmountInput;
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
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.exceptions.PersistenceException;

@Slf4j
@Singleton
public class DirectDebitServiceImpl implements DirectDebitService {

    /** How long a customer has to answer a proposal. */
    static final Duration PROPOSAL_TTL = Duration.ofDays(7);

    /** A reservation unsettled for this long is presumed abandoned by a crash. */
    static final Duration STALE_RESERVATION = Duration.ofMinutes(5);

    static final int JOB_BATCH = 100;
    static final int MAX_REFERENCE = 100;

    private final DirectDebitMapper mapper;
    private final DirectDebitStore store;
    private final FirmAccountsMapper firmAccounts;
    private final FirmService firms;
    private final FirmPlayerService players;
    private final FirmNotificationService notifications;
    private final TreasuryApi treasury;
    private final FeatureFlagApi flags;
    private final BusinessMessages message;
    private final Clock clock;

    @Inject
    public DirectDebitServiceImpl(
            DirectDebitMapper mapper,
            DirectDebitStore store,
            FirmAccountsMapper firmAccounts,
            FirmService firms,
            FirmPlayerService players,
            FirmNotificationService notifications,
            TreasuryApi treasury,
            FeatureFlagApi flags,
            BusinessMessages message) {
        this(mapper, store, firmAccounts, firms, players, notifications, treasury, flags, message, Clock.systemUTC());
    }

    /** Test seam: pins "now". */
    DirectDebitServiceImpl(
            DirectDebitMapper mapper,
            DirectDebitStore store,
            FirmAccountsMapper firmAccounts,
            FirmService firms,
            FirmPlayerService players,
            FirmNotificationService notifications,
            TreasuryApi treasury,
            FeatureFlagApi flags,
            BusinessMessages message,
            Clock clock) {
        this.mapper = mapper;
        this.store = store;
        this.firmAccounts = firmAccounts;
        this.firms = firms;
        this.players = players;
        this.notifications = notifications;
        this.treasury = treasury;
        this.flags = flags;
        this.message = message;
        this.clock = clock;
    }

    @Override
    public boolean isEnabled() {
        return flags.isEnabled(FEATURE_FLAG);
    }

    // ---- Mandate lifecycle -----------------------------------------------------

    @Override
    public DirectDebitMandate propose(
            int firmId,
            UUID actorId,
            UUID payerId,
            DirectDebitKind kind,
            DirectDebitPeriod period,
            BigDecimal limit,
            String reference) {
        requireEnabled();
        BigDecimal amount = AmountInput.positive(limit)
                .orElseThrow(() -> new BadCommandException("business.finance.invalid-amount"));
        String ref = reference == null ? "" : reference.strip();
        if (ref.isEmpty() || ref.length() > MAX_REFERENCE) {
            throw new BadCommandException("business.debit.propose.invalid-reference", "max", MAX_REFERENCE);
        }
        Firm firm = firms.getFirmById(firmId);
        if (firm == null) {
            throw new NotFoundException("business.general.firm-not-found");
        }
        Integer payeeAccountId = firm.getDefaultAccountId();
        Account payee = payeeAccountId == null ? null : treasury.getAccountById(payeeAccountId);
        if (payee == null || payee.isArchived()) {
            throw new ConflictException("business.finance.no-account");
        }
        // The customer is the transfer's authorizer and can never authorise the firm's
        // account, so every collection into an authorization-gated account would fail.
        if (payee.isRequiresAuthorization()) {
            throw new ConflictException("business.debit.propose.payee-requires-authorization");
        }
        Account payer = treasury.resolveOrCreatePersonal(payerId);
        if (java.util.Objects.equals(payer.getAccountId(), payee.getAccountId())) {
            throw new BadCommandException("business.debit.propose.same-account");
        }

        LocalDateTime now = now();
        if (mapper.listOpenForPayer(payerId.toString()).stream()
                .anyMatch(m -> m.getFirmId() == firmId
                        && m.getStatus() == DirectDebitStatus.PENDING
                        && m.getExpiresAt().isAfter(now))) {
            throw new ConflictException("business.debit.propose.pending-exists");
        }
        // A lapsed proposal still holds the one-pending slot until the job expires it.
        mapper.expireLapsedProposal(firmId, payerId.toString(), now);

        DirectDebitMandate mandate = new DirectDebitMandate();
        mandate.setFirmId(firmId);
        mandate.setPayeeAccountId(payee.getAccountId());
        mandate.setPayerAccountId(payer.getAccountId());
        mandate.setPayerUuid(payerId.toString());
        mandate.setKind(kind);
        mandate.setPeriod(period);
        mandate.setCap(amount);
        mandate.setAmount(kind == DirectDebitKind.RECURRING ? amount : null);
        mandate.setReference(ref);
        mandate.setStatus(DirectDebitStatus.PENDING);
        mandate.setProposedByUuid(actorId.toString());
        mandate.setProposedAt(now);
        mandate.setExpiresAt(now.plus(PROPOSAL_TTL));
        mandate.setMissedPeriods(0);
        try {
            mapper.insertMandate(mandate);
        } catch (PersistenceException ex) {
            // Lost a race with a concurrent proposal to the same customer.
            if (ex.getCause() instanceof SQLIntegrityConstraintViolationException) {
                throw new ConflictException("business.debit.propose.pending-exists");
            }
            throw ex;
        }
        return mandate;
    }

    @Override
    public DirectDebitMandate accept(long mandateId, UUID payerId) {
        requireEnabled();
        DirectDebitMandate mandate = requirePayer(mandateId, payerId);
        LocalDateTime now = now();
        // Recurring mandates are due immediately: the first period is collected on the next job pass.
        LocalDateTime nextDue = mandate.getKind() == DirectDebitKind.RECURRING ? now : null;
        if (mapper.accept(mandateId, now, nextDue) != 1) {
            throw new ConflictException("business.debit.not-pending", "id", mandateId);
        }
        DirectDebitMandate accepted = mapper.findMandate(mandateId);
        notifications.notifyFirm(
                accepted.getFirmId(),
                "business.debit.notify.accepted",
                "id",
                mandateId,
                "payer",
                playerName(payerId),
                "firm",
                firmName(accepted.getFirmId()));
        return accepted;
    }

    @Override
    public DirectDebitMandate reject(long mandateId, UUID payerId) {
        requireEnabled();
        DirectDebitMandate mandate = requirePayer(mandateId, payerId);
        if (mandate.getStatus() != DirectDebitStatus.PENDING
                || mapper.end(
                                mandateId,
                                DirectDebitStatus.REJECTED.name(),
                                payerId.toString(),
                                "Rejected by customer",
                                now())
                        != 1) {
            throw new ConflictException("business.debit.not-pending", "id", mandateId);
        }
        return mapper.findMandate(mandateId);
    }

    @Override
    public DirectDebitMandate revoke(long mandateId, UUID payerId) {
        requireEnabled();
        DirectDebitMandate mandate = requirePayer(mandateId, payerId);
        if (mapper.end(
                        mandateId,
                        DirectDebitStatus.CANCELLED.name(),
                        payerId.toString(),
                        "Cancelled by customer",
                        now())
                != 1) {
            throw new ConflictException("business.debit.already-ended", "id", mandateId);
        }
        notifications.notifyFirm(
                mandate.getFirmId(),
                "business.debit.notify.revoked",
                "id",
                mandateId,
                "payer",
                playerName(payerId),
                "firm",
                firmName(mandate.getFirmId()));
        return mapper.findMandate(mandateId);
    }

    @Override
    public DirectDebitMandate resume(long mandateId, UUID payerId) {
        requireEnabled();
        requirePayer(mandateId, payerId);
        if (mapper.resume(mandateId, now()) != 1) {
            throw new ConflictException("business.debit.not-suspended", "id", mandateId);
        }
        return mapper.findMandate(mandateId);
    }

    @Override
    public DirectDebitMandate cancelByFirm(int firmId, long mandateId, UUID actorId) {
        requireEnabled();
        DirectDebitMandate mandate = mapper.findMandate(mandateId);
        if (mandate == null || mandate.getFirmId() != firmId) {
            throw new NotFoundException("business.debit.not-found", "id", mandateId);
        }
        if (mapper.end(mandateId, DirectDebitStatus.CANCELLED.name(), actorId.toString(), "Cancelled by firm", now())
                != 1) {
            throw new ConflictException("business.debit.already-ended", "id", mandateId);
        }
        message.send(
                UUID.fromString(mandate.getPayerUuid()),
                "business.debit.notify.cancelled-by-firm",
                "id",
                mandateId,
                "firm",
                firmName(firmId));
        return mapper.findMandate(mandateId);
    }

    @Override
    public List<DirectDebitMandate> listForPayer(UUID payerId) {
        requireEnabled();
        return mapper.listOpenForPayer(payerId.toString());
    }

    @Override
    public List<DirectDebitMandate> listForFirm(int firmId) {
        requireEnabled();
        return mapper.listOpenForFirm(firmId);
    }

    // ---- Collections -------------------------------------------------------------

    @Override
    public Charge charge(int firmId, long mandateId, UUID actorId, BigDecimal amount, String memo) {
        requireEnabled();
        BigDecimal value = AmountInput.positive(amount)
                .orElseThrow(() -> new BadCommandException("business.finance.invalid-amount"));
        String note = memo == null || memo.isBlank() ? null : memo.strip();
        if (note != null && note.length() > MAX_REFERENCE) {
            throw new BadCommandException("business.debit.charge.memo-too-long", "max", MAX_REFERENCE);
        }
        LocalDateTime now = now();
        Reservation reservation = store.reserveOnDemand(firmId, mandateId, actorId.toString(), value, note, now);
        DirectDebitMandate mandate = reservation.mandate();

        Transfer result = transfer(reservation, actorId, true);
        long collectionId = reservation.collection().getCollectionId();
        try {
            store.settleOnDemand(collectionId, result.txnId(), result.failure(), now());
        } catch (RuntimeException e) {
            // The money has (or hasn't) already moved; the job settles the stale
            // reservation, so report the charge's real outcome rather than this error.
            log.warn(
                    "Direct debit: settling collection {} failed, left for recovery: {}", collectionId, e.getMessage());
        }
        notifyOnDemand(mandate, value, result.txnId() != null);
        if (result.txnId() == null) {
            throw new ExceedsLimitException("business.debit.charge.failed", "reason", result.failure());
        }
        return new Charge(collectionId, result.txnId(), value, reservation.remaining());
    }

    @Override
    public JobResult runJob() {
        if (!isEnabled()) {
            return new JobResult(0, 0, 0, 0);
        }
        LocalDateTime now = now();
        int expired = mapper.expireLapsedProposals(now);

        int recovered = 0;
        for (long collectionId : mapper.staleReservations(now.minus(STALE_RESERVATION), JOB_BATCH)) {
            try {
                if (recover(collectionId)) recovered++;
            } catch (RuntimeException e) {
                log.warn("Direct debit: recovering collection {} failed: {}", collectionId, e.getMessage());
            }
        }

        int collected = 0;
        int failed = 0;
        for (long mandateId : mapper.dueRecurring(now, JOB_BATCH)) {
            try {
                Optional<Boolean> outcome = collectRecurring(mandateId);
                if (outcome.isPresent()) {
                    if (outcome.get()) collected++;
                    else failed++;
                }
            } catch (RuntimeException e) {
                log.warn("Direct debit: collecting mandate {} failed: {}", mandateId, e.getMessage());
            }
        }
        return new JobResult(expired, recovered, collected, failed);
    }

    /** Collects one due recurring mandate; empty when nothing was attempted. */
    private Optional<Boolean> collectRecurring(long mandateId) {
        Optional<Reservation> reserved = store.reserveRecurring(mandateId, now());
        if (reserved.isEmpty()) return Optional.empty();
        Reservation reservation = reserved.get();
        DirectDebitMandate mandate = reservation.mandate();

        Transfer result = transfer(reservation, UUID.fromString(mandate.getProposedByUuid()), true);
        RecurringOutcome outcome = store.settleRecurring(reservation, result.txnId(), result.failure(), now());
        notifyRecurring(mandate, reservation.collection().getAmount(), outcome);
        return Optional.of(outcome == RecurringOutcome.COLLECTED);
    }

    /**
     * Settles a reservation left RESERVED by a crash. The transfer is replayed with
     * the same dedup key, so one that already happened collapses onto its txn and
     * is just recorded. The reservation was admitted while the mandate was active
     * and within its cap, so it is completed even if the mandate ended since.
     */
    private boolean recover(long collectionId) {
        Optional<Reservation> locked = store.lockStale(collectionId);
        if (locked.isEmpty()) return false;
        Reservation reservation = locked.get();
        UUID initiator = UUID.fromString(reservation.collection().getInitiatorUuid());
        Transfer result = transfer(reservation, initiator, false);
        DirectDebitMandate mandate = reservation.mandate();
        BigDecimal amount = reservation.collection().getAmount();
        if (mandate.getKind() == DirectDebitKind.RECURRING) {
            notifyRecurring(
                    mandate, amount, store.settleRecurring(reservation, result.txnId(), result.failure(), now()));
        } else {
            store.settleOnDemand(collectionId, result.txnId(), result.failure(), now());
            notifyOnDemand(mandate, amount, result.txnId() != null);
        }
        return true;
    }

    private record Transfer(Long txnId, String failure) {}

    /**
     * Moves the reserved amount from the customer's personal account into the
     * firm's account. The customer is recorded as the authorizer — the mandate is
     * their authority — and the payer's own authorization requirement still
     * applies (no admin bypass). {@code checkFirm} rejects firms that have since
     * disbanded or no longer own the payee account.
     */
    private Transfer transfer(Reservation reservation, UUID initiator, boolean checkFirm) {
        DirectDebitMandate mandate = reservation.mandate();
        BigDecimal amount = reservation.collection().getAmount();
        try {
            if (checkFirm) {
                Firm firm = firms.getFirmById(mandate.getFirmId());
                if (firm == null || !firmAccounts.isFirmAccount(mandate.getFirmId(), mandate.getPayeeAccountId())) {
                    mapper.end(
                            mandate.getMandateId(),
                            DirectDebitStatus.CANCELLED.name(),
                            null,
                            "Firm or receiving account no longer available",
                            now());
                    return new Transfer(null, "The firm or its receiving account is no longer available");
                }
            }
            if (!treasury.hasFunds(mandate.getPayerAccountId(), amount)) {
                return new Transfer(null, "Insufficient funds");
            }
            String message = "Direct debit: " + firmName(mandate.getFirmId()) + " — " + mandate.getReference()
                    + (reservation.collection().getMemo() == null
                            ? ""
                            : " (" + reservation.collection().getMemo() + ")")
                    + " #" + mandate.getMandateId();
            long txnId = treasury.transfer(new TransferRequest(
                    mandate.getPayerAccountId(),
                    mandate.getPayeeAccountId(),
                    amount,
                    message.length() <= 255 ? message : message.substring(0, 255),
                    initiator,
                    UUID.fromString(mandate.getPayerUuid()),
                    PLUGIN_SYSTEM,
                    // One key per collection, shared by its retries and crash recovery: a
                    // transfer that committed but was reported failed collapses instead of
                    // debiting the customer twice.
                    Idempotency.sha256(
                            PLUGIN_SYSTEM + ":" + reservation.collection().getCollectionId())));
            return new Transfer(txnId, null);
        } catch (RuntimeException e) {
            log.warn(
                    "Direct debit transfer for mandate {} (collection {}) failed: {}",
                    mandate.getMandateId(),
                    reservation.collection().getCollectionId(),
                    e.getMessage());
            return new Transfer(null, e.getMessage());
        }
    }

    // ---- Notifications -----------------------------------------------------------

    /** Tells the customer an on-demand charge was taken or failed; never fails the charge. */
    private void notifyOnDemand(DirectDebitMandate mandate, BigDecimal amount, boolean collected) {
        try {
            if (collected) {
                notifyCollected(mandate, amount);
            } else {
                message.send(
                        UUID.fromString(mandate.getPayerUuid()),
                        "business.debit.notify.failed",
                        "id",
                        mandate.getMandateId(),
                        "firm",
                        firmName(mandate.getFirmId()),
                        "amount",
                        treasury.formatAmount(amount));
            }
        } catch (RuntimeException e) {
            log.warn("Direct debit: notifying mandate {} failed: {}", mandate.getMandateId(), e.getMessage());
        }
    }

    private void notifyCollected(DirectDebitMandate mandate, BigDecimal amount) {
        message.send(
                UUID.fromString(mandate.getPayerUuid()),
                "business.debit.notify.collected",
                "id",
                mandate.getMandateId(),
                "firm",
                firmName(mandate.getFirmId()),
                "amount",
                treasury.formatAmount(amount),
                "reference",
                mandate.getReference());
    }

    private void notifyRecurring(DirectDebitMandate mandate, BigDecimal amount, RecurringOutcome outcome) {
        if (outcome == RecurringOutcome.COLLECTED) {
            notifyCollected(mandate, amount);
            return;
        }
        UUID payer = UUID.fromString(mandate.getPayerUuid());
        String firm = firmName(mandate.getFirmId());
        String formatted = treasury.formatAmount(amount);
        message.send(
                payer, "business.debit.notify.failed", "id", mandate.getMandateId(), "firm", firm, "amount", formatted);
        if (outcome == RecurringOutcome.SUSPENDED) {
            message.send(payer, "business.debit.notify.suspended", "id", mandate.getMandateId(), "firm", firm);
        }
        if (outcome != RecurringOutcome.RETRY_SCHEDULED) {
            notifications.notifyFirm(
                    mandate.getFirmId(),
                    outcome == RecurringOutcome.SUSPENDED
                            ? "business.debit.notify.firm-suspended"
                            : "business.debit.notify.firm-missed",
                    "id",
                    mandate.getMandateId(),
                    "payer",
                    playerName(payer),
                    "firm",
                    firm,
                    "amount",
                    formatted);
        }
    }

    // ---- Helpers -----------------------------------------------------------------

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new ConflictException("business.debit.disabled");
        }
    }

    private DirectDebitMandate requirePayer(long mandateId, UUID payerId) {
        DirectDebitMandate mandate = mapper.findMandate(mandateId);
        if (mandate == null) {
            throw new NotFoundException("business.debit.not-found", "id", mandateId);
        }
        if (!payerId.toString().equalsIgnoreCase(mandate.getPayerUuid())) {
            throw new NoPermissionException("business.debit.not-yours", "id", mandateId);
        }
        return mandate;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }

    private String firmName(int firmId) {
        Firm firm = firms.getAnyFirmById(firmId);
        return firm == null ? "#" + firmId : firm.getDisplayName();
    }

    private String playerName(UUID uuid) {
        return players.findByUuid(uuid).map(FirmPlayer::getCurrentName).orElse(uuid.toString());
    }
}
