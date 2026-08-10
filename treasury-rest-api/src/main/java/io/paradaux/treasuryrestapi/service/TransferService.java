package io.paradaux.treasuryrestapi.service;

import io.paradaux.common.OverdraftPolicy;
import io.paradaux.treasuryrestapi.dto.FirmTransferRequest;
import io.paradaux.treasuryrestapi.dto.PlayerTransferRequest;
import io.paradaux.treasuryrestapi.dto.TransferRequest;
import io.paradaux.treasuryrestapi.dto.TransferResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.mapper.AccountMapper;
import io.paradaux.treasuryrestapi.mapper.FirmMapper;
import io.paradaux.treasuryrestapi.mapper.LedgerMapper;
import io.paradaux.treasuryrestapi.model.Account;
import io.paradaux.treasuryrestapi.model.AccountBalance;
import io.paradaux.treasuryrestapi.model.Firm;
import io.paradaux.treasuryrestapi.model.LedgerPosting;
import io.paradaux.treasuryrestapi.model.LedgerTxn;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    /**
     * Max characters accepted in the raw {@code amount} string. Bounds the cost of
     * {@code new BigDecimal(String)} (digit accumulation is ~O(n²)), so a multi-MB
     * body of digits can't burn CPU before validation. A DECIMAL(19,2) value needs
     * at most ~21 chars ("-" + 17 int + "." + 2 frac); 64 is generous headroom.
     */
    private static final int MAX_AMOUNT_STRING_LENGTH = 64;
    /** Ledger amounts are DECIMAL(19,2): at most 2 fractional digits … */
    private static final int MAX_AMOUNT_SCALE = 2;
    /** … and at most 17 integer digits (19 − 2). */
    private static final int MAX_AMOUNT_INTEGER_DIGITS = 17;

    private final AccountMapper accountMapper;
    private final LedgerMapper ledgerMapper;
    private final FirmMapper firmMapper;

    public TransferService(AccountMapper accountMapper, LedgerMapper ledgerMapper, FirmMapper firmMapper) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.firmMapper = firmMapper;
    }

    /**
     * Executes a transfer from the token's account to another account.
     * Implements spec steps 2–14 within a single DB transaction.
     * The idempotency check (step 5) runs at the start of the transaction;
     * the SELECT FOR UPDATE locks (steps 9, 11) are acquired after validation.
     *
     * @param idempotencyKey raw Idempotency-Key header value, or null if not supplied
     */
    @Transactional
    public TransferResponse transfer(VerifiedToken verified,
                                     TransferRequest request,
                                     String idempotencyKey) {
        log.debug("Processing transfer: fromAccount={} toAccount={} amount={} idempotencyKey={}",
                verified.accountId(), request.toAccountId(), request.amount(),
                idempotencyKey != null ? "present" : "absent");

        // Step 2: toAccountId present and positive
        if (request.toAccountId() == null || request.toAccountId() <= 0) {
            log.warn("Transfer rejected: invalid toAccountId={}", request.toAccountId());
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "Field 'toAccountId' is required and must be a positive integer.");
        }

        // Step 3: amount present, parses as a positive decimal, and bounded to the
        // DECIMAL(19,2) ledger column. The bound is security-critical, not cosmetic
        // — see validatedAmount.
        BigDecimal amount = validatedAmount(request.amount());

        long fromAccountId;
        if ("BUSINESS".equals(verified.keyType())) {
            // BUSINESS keys must specify the firm account to debit in the request body
            if (request.fromAccountId() == null || request.fromAccountId() <= 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                        "Field 'fromAccountId' is required for BUSINESS API keys.");
            }
            if (firmMapper.isFirmAccount(verified.firmId(), request.fromAccountId()) == 0) {
                log.warn("Transfer rejected: accountId={} does not belong to firmId={}",
                        request.fromAccountId(), verified.firmId());
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "Account does not belong to the firm associated with this API key.");
            }
            fromAccountId = request.fromAccountId();
        } else {
            // PERSONAL / GOVERNMENT: source account is always the acc claim from the JWT
            fromAccountId = verified.accountId();
        }
        long toAccountId = request.toAccountId();
        // ledger_txns.message is NOT NULL in the canonical schema; the API
        // spec marks memo as optional, so coerce null to empty string before
        // persistence. Surfaced by integration tests where any null-memo
        // transfer 500'd on the insert constraint.
        String memo = request.memo() == null ? "" : request.memo();

        return executeTransfer(fromAccountId, toAccountId, amount, memo,
                verified.ownerUuid(), idempotencyKey, false);
    }

    /**
     * SERVICE-scoped admin transfer between two arbitrary accounts with a memo
     * (PAR-217). Validates + bounds the amount like the token path, records the
     * SERVICE key's owner as the ledger initiator, and goes through the same
     * {@link #executeTransfer} core. Does NOT bypass the requires-authorization
     * guard — an account needing an in-game authorizer is rejected (403).
     */
    @Transactional
    public TransferResponse adminTransfer(VerifiedToken verified,
                                          Long fromAccountId,
                                          Long toAccountId,
                                          String amount,
                                          String memo,
                                          String idempotencyKey) {
        io.paradaux.treasuryrestapi.security.AdminScope.require(verified);
        if (fromAccountId == null || fromAccountId <= 0 || toAccountId == null || toAccountId <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "Fields 'fromAccountId' and 'toAccountId' are required and must be positive.");
        }
        BigDecimal amt = validatedAmount(amount);
        return executeTransfer(fromAccountId, toAccountId, amt, memo == null ? "" : memo,
                verified.ownerUuid(), idempotencyKey, /* bypassAuthRequired */ false);
    }

    /**
     * Core transfer pipeline (spec steps 4–14) for an already-resolved source and
     * destination. Shared by the token-scoped {@link #transfer} path and the admin
     * firm-disband sweep (which moves money between arbitrary accounts). Runs in the
     * caller's transaction when one is already active.
     *
     * @param initiator          recorded as {@code ledger_txns.initiator} (the acting party)
     * @param bypassAuthRequired skip the requires-authorization rejection — set only for
     *                           privileged system flows (admin disband), never for
     *                           ordinary API transfers
     */
    @Transactional
    public TransferResponse executeTransfer(long fromAccountId,
                                            long toAccountId,
                                            BigDecimal amount,
                                            String memo,
                                            UUID initiator,
                                            String idempotencyKey,
                                            boolean bypassAuthRequired) {
        // ledger_txns.message is VARCHAR(255) and NOT NULL. Pre-validate so a too-long
        // memo surfaces as a clean 400 INVALID_BODY rather than a driver-level 500.
        String safeMemo = memo == null ? "" : memo;
        if (safeMemo.length() > 255) {
            log.warn("Transfer rejected: memo length {} exceeds 255", safeMemo.length());
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "Field 'memo' must be at most 255 characters.");
        }

        // Step 4: no self-transfers
        if (fromAccountId == toAccountId) {
            log.warn("Transfer rejected: self-transfer attempted on accountId={}", fromAccountId);
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_TRANSFER",
                    "Source and destination accounts must be different.");
        }

        // Step 5: idempotency check.
        // The dedup key is scoped to the source account, not global. client_dedup_key
        // carries a UNIQUE constraint, so a key derived from the raw header alone lets
        // ANY caller's Idempotency-Key collide with another's: the second caller is
        // either blocked (409 IDEMPOTENCY_CONFLICT on a body mismatch) or, on a body
        // match, handed back a transaction that isn't theirs. Folding fromAccountId in
        // confines collisions to the one account a caller is already authorised to debit.
        String dedupKey = idempotencyKey != null
                ? sha256Hex(fromAccountId + ":" + idempotencyKey) : null;
        if (dedupKey != null) {
            LedgerTxn existing = ledgerMapper.findByDedupKey(dedupKey);
            if (existing != null) {
                log.info("Idempotent replay: dedupKey found for txnId={}, returning cached response", existing.getTxnId());
                return replayIdempotent(existing, fromAccountId, toAccountId, amount, safeMemo);
            }
            log.debug("Idempotency key provided but no prior transaction found; proceeding with new transfer");
        }

        // Step 6: source account must exist and not be archived.
        // Note on parity with the in-process plugin engine (LedgerServiceImpl): the plugin
        // re-reads the source's overdraft flags (allow_overdraft/credit_limit) under a
        // shared row lock (ADT-10) to exclude a concurrent flag flip mid-transfer. This
        // engine deliberately reads them from this unlocked findById instead — and that is
        // safe here, NOT an oversight: treasury-rest-api has no code path that writes those
        // flags at all (only the plugin's admin tooling flips them), and no normal user can
        // trigger a flip. If parity were ever needed, the correct change is a shared-lock
        // read of the accounts row BEFORE the balance FOR UPDATE locks below (accounts-then-
        // balances, matching the plugin's order) — do not "fix" it as a deadlock issue.
        Account source = accountMapper.findById(fromAccountId);
        if (source == null || source.isArchived()) {
            log.warn("Transfer rejected: source accountId={} not found or archived", fromAccountId);
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND",
                    "Source account not found.");
        }

        // Step 7: destination account must exist and not be archived
        Account destination = accountMapper.findById(toAccountId);
        if (destination == null || destination.isArchived()) {
            log.warn("Transfer rejected: destination accountId={} not found or archived", toAccountId);
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND",
                    "Destination account not found.");
        }

        // Step 8: reject accounts that require in-game authorizer flows — unless this is
        // a privileged system flow (admin disband) that is authorised out-of-band.
        if (!bypassAuthRequired && (source.isRequiresAuthorization() || destination.isRequiresAuthorization())) {
            log.warn("Transfer rejected: authorization required (source={}, dest={})",
                    source.isRequiresAuthorization(), destination.isRequiresAuthorization());
            throw new ApiException(HttpStatus.FORBIDDEN, "AUTHORIZATION_REQUIRED",
                    "One or both accounts require an in-game authorizer and cannot be used via the REST API.");
        }

        // Steps 9 + 11: pessimistic locks on both balances. Lock acquisition
        // order is by ascending account_id — NOT by from-then-to — so that
        // concurrent A→B and B→A transfers can't deadlock each other. Without
        // this ordering, MariaDB will detect the deadlock and roll one back,
        // surfacing as a 500 to whichever client lost the race.
        long firstLockId  = Math.min(fromAccountId, toAccountId);
        long secondLockId = Math.max(fromAccountId, toAccountId);
        AccountBalance firstLock  = accountMapper.findBalanceForUpdate(firstLockId);
        AccountBalance secondLock = accountMapper.findBalanceForUpdate(secondLockId);
        AccountBalance sourceBalance = (fromAccountId == firstLockId) ? firstLock  : secondLock;
        AccountBalance destBalance   = (fromAccountId == firstLockId) ? secondLock : firstLock;
        log.debug("Locked balances in id-asc order: source(accountId={} balance={} version={}), "
                + "dest(accountId={} balance={} version={})",
                fromAccountId, sourceBalance.getBalance(), sourceBalance.getVersion(),
                toAccountId, destBalance.getBalance(), destBalance.getVersion());

        // Step 10: overdraft check (after both locks held — order is irrelevant for the check itself).
        // Defers to the shared OverdraftPolicy so this engine and the in-process Treasury plugin
        // interpret (allow_overdraft, credit_limit) identically (PAR-319):
        //   SYSTEM account                            → unlimited (ignores credit limits)
        //   allow_overdraft = false                   → floor 0 (credit_limit ignored)
        //   allow_overdraft = true, credit_limit < 0  → unlimited faucet/sink
        //   allow_overdraft = true, credit_limit >= 0 → floor -credit_limit
        boolean sourceIsSystem = "SYSTEM".equals(source.getAccountType());
        if (!OverdraftPolicy.isWithinFloor(sourceBalance.getBalance(), amount,
                source.isAllowOverdraft(), source.getCreditLimit(), sourceIsSystem)) {
            log.warn("Transfer rejected: insufficient funds on accountId={} (balance={}, amount={}, "
                    + "allowOverdraft={}, creditLimit={}, accountType={})",
                    fromAccountId, sourceBalance.getBalance(), amount,
                    source.isAllowOverdraft(), source.getCreditLimit(), source.getAccountType());
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INSUFFICIENT_FUNDS",
                    "Source account has insufficient funds.");
        }

        return postTransfer(fromAccountId, toAccountId, amount, safeMemo, initiator, dedupKey);
    }

    /**
     * Sweeps the <em>freshly locked</em> positive balance of {@code fromAccountId}
     * into {@code toAccountId} in a single ledger transaction. Unlike
     * {@link #executeTransfer}, the moved amount is not a caller-supplied snapshot: it
     * is read under the {@code SELECT ... FOR UPDATE} lock, so a concurrent credit or
     * debit that lands between a caller's snapshot and this call cannot leave a residual
     * behind or make the sweep overdraw (conservation stays exact). Used by the admin
     * firm-disband sweep, which must not trust {@code account_balances_mat} snapshots.
     *
     * <p>Returns {@code null} when the locked balance is zero-or-negative (nothing to
     * move) so the caller can archive the account without emitting an empty transfer.
     *
     * @return the transfer receipt, or {@code null} if the locked balance was not positive
     */
    @Transactional
    public TransferResponse sweepAll(long fromAccountId,
                                     long toAccountId,
                                     String memo,
                                     UUID initiator) {
        String safeMemo = memo == null ? "" : memo;
        if (fromAccountId == toAccountId) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_TRANSFER",
                    "Source and destination accounts must be different.");
        }

        // Lock both balance rows in ascending account-id order — identical ordering to
        // executeTransfer — so a sweep and a concurrent A→B / B→A transfer can't deadlock.
        long firstLockId  = Math.min(fromAccountId, toAccountId);
        long secondLockId = Math.max(fromAccountId, toAccountId);
        AccountBalance firstLock  = accountMapper.findBalanceForUpdate(firstLockId);
        AccountBalance secondLock = accountMapper.findBalanceForUpdate(secondLockId);
        AccountBalance sourceBalance = (fromAccountId == firstLockId) ? firstLock : secondLock;
        if (sourceBalance == null) {
            // Two-engine parity with LedgerServiceImpl.sweepAll: a missing source balance
            // row is a state error, not a silent no-op. Not live-reachable today (disband
            // callers only sweep accounts that exist), but keep the engines equivalent.
            throw new IllegalStateException("Missing balance row for source account " + fromAccountId);
        }

        BigDecimal amount = sourceBalance.getBalance();
        if (amount == null || amount.signum() <= 0) {
            log.debug("Sweep no-op: locked balance on accountId={} is {} (nothing to move)",
                    fromAccountId, amount);
            return null;
        }

        log.debug("Sweeping locked balance {} from accountId={} to accountId={}",
                amount.toPlainString(), fromAccountId, toAccountId);
        return postTransfer(fromAccountId, toAccountId, amount, safeMemo, initiator, /* dedupKey */ null);
    }

    /**
     * Reads an account's balance under a {@code SELECT ... FOR UPDATE} lock, held for
     * the remainder of the caller's transaction. Used by the admin disband flow to
     * decide — from the authoritative, concurrency-safe value rather than a stale
     * {@code account_balances_mat} snapshot — whether an account with no sweep
     * destination truly still holds money. Returns {@code null} if no balance row
     * exists for the account.
     */
    @Transactional
    public BigDecimal lockedBalance(long accountId) {
        AccountBalance locked = accountMapper.findBalanceForUpdate(accountId);
        return locked == null ? null : locked.getBalance();
    }

    /**
     * Records the money movement once both balance rows are locked and the amount is
     * settled: inserts the {@code ledger_txns} row and the debit/credit postings, then
     * builds the receipt. The {@code account_balances_mat} deltas are applied by the
     * {@code trg_postings_ai} trigger on {@code ledger_postings} — never an
     * application-side UPDATE. Shared by {@link #executeTransfer} and {@link #sweepAll}
     * so both write the ledger identically.
     */
    private TransferResponse postTransfer(long fromAccountId,
                                          long toAccountId,
                                          BigDecimal amount,
                                          String safeMemo,
                                          UUID initiator,
                                          String dedupKey) {
        Instant now = Instant.now();
        LocalDateTime settlementTime = LocalDateTime.ofInstant(now, ZoneOffset.UTC);

        // Step 12: insert ledger_txns
        LedgerTxn txn = LedgerTxn.builder()
                .message(safeMemo)
                .settlementTime(settlementTime)
                .initiatorUuidBin(initiator)
                .pluginSystem("rest-api")
                .clientDedupKey(dedupKey)
                .build();
        ledgerMapper.insertTxn(txn);
        log.debug("Inserted ledger transaction: txnId={}", txn.getTxnId());

        // Step 13: insert two postings — debit source, credit destination
        ledgerMapper.insertPosting(LedgerPosting.builder()
                .txnId(txn.getTxnId())
                .accountId(fromAccountId)
                .amount(amount.negate())
                .memo(safeMemo)
                .build());
        ledgerMapper.insertPosting(LedgerPosting.builder()
                .txnId(txn.getTxnId())
                .accountId(toAccountId)
                .amount(amount)
                .memo(safeMemo)
                .build());
        log.debug("Inserted debit and credit postings for txnId={}", txn.getTxnId());

        // Step 14: balance deltas are applied by the trg_postings_ai trigger
        // on ledger_postings — no application-side UPDATE here. This is
        // intentional: the canonical schema makes the DB the single writer
        // for account_balances_mat so that the in-game Treasury plugin and
        // this REST service can post concurrently without racing on a
        // Java-side delta. The pessimistic SELECT FOR UPDATE locks
        // (sourceBalance, destBalance) above serialise concurrent writers
        // long enough for the trigger-driven update to commit.

        log.info("Transfer completed: txnId={} fromAccount={} toAccount={} amount={} initiator={}",
                txn.getTxnId(), fromAccountId, toAccountId, amount.toPlainString(), initiator);

        return new TransferResponse(
                txn.getTxnId(),
                fromAccountId,
                toAccountId,
                amount.toPlainString(),
                safeMemo,
                now
        );
    }

    /**
     * Resolves the destination to the named firm's default account, then delegates
     * to the standard transfer pipeline. Supports the same idempotency semantics.
     */
    @Transactional
    public TransferResponse transferToFirm(VerifiedToken verified,
                                           FirmTransferRequest request,
                                           String idempotencyKey) {
        if (request.toFirm() == null || request.toFirm().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "Field 'toFirm' is required.");
        }

        Firm firm = firmMapper.findFirmByDisplayName(request.toFirm());
        if (firm == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FIRM_NOT_FOUND",
                    "Firm '" + request.toFirm() + "' not found.");
        }
        if (firm.getDefaultAccountId() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "NO_DEFAULT_ACCOUNT",
                    "Firm '" + request.toFirm() + "' has no default account configured.");
        }

        log.debug("Resolved toFirm='{}' to toAccountId={}", request.toFirm(), firm.getDefaultAccountId());

        return transfer(verified,
                new TransferRequest(request.fromAccountId(), firm.getDefaultAccountId(), request.amount(), request.memo()),
                idempotencyKey);
    }

    /**
     * Resolves a player (by UUID or in-game name) to their PERSONAL account, then
     * delegates to the standard transfer pipeline. Exactly one of
     * {@code toPlayerUuid} / {@code toPlayerName} must be supplied. The target
     * must already have a PERSONAL account — this never mints one (no faucet
     * exposure via REST). Same idempotency semantics as {@link #transfer}.
     */
    @Transactional
    public TransferResponse transferToPlayer(VerifiedToken verified,
                                             PlayerTransferRequest request,
                                             String idempotencyKey) {
        boolean hasUuid = request.toPlayerUuid() != null && !request.toPlayerUuid().isBlank();
        boolean hasName = request.toPlayerName() != null && !request.toPlayerName().isBlank();
        if (hasUuid == hasName) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                    "Provide exactly one of 'toPlayerUuid' or 'toPlayerName'.");
        }

        UUID targetUuid;
        if (hasUuid) {
            try {
                targetUuid = UUID.fromString(request.toPlayerUuid().trim());
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BODY",
                        "Field 'toPlayerUuid' is not a valid UUID.");
            }
        } else {
            targetUuid = firmMapper.findPlayerUuidByName(request.toPlayerName().trim());
            if (targetUuid == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND",
                        "No player known by the name '" + request.toPlayerName() + "'.");
            }
        }

        Long toAccountId = accountMapper.findPersonalAccountIdByOwner(targetUuid);
        if (toAccountId == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND",
                    "The target player has no personal account.");
        }

        log.debug("Resolved player ({}) to toAccountId={}",
                hasUuid ? targetUuid : request.toPlayerName(), toAccountId);

        return transfer(verified,
                new TransferRequest(request.fromAccountId(), toAccountId, request.amount(), request.memo()),
                idempotencyKey);
    }

    /**
     * Returns the original response for a duplicate idempotency key, verifying
     * the stored transfer matches the incoming body in full (from + to + amount
     * + memo) before replaying. The returned response echoes the *stored*
     * values, not the request — a same-key replay with mismatched body is a
     * 409 IDEMPOTENCY_CONFLICT, never a silent stored-vs-claimed mismatch.
     */
    private TransferResponse replayIdempotent(LedgerTxn existing,
                                               long fromAccountId,
                                               long toAccountId,
                                               BigDecimal amount,
                                               String memo) {
        LedgerPosting credit = ledgerMapper.findCreditPostingByTxnId(existing.getTxnId());
        LedgerPosting debit  = ledgerMapper.findDebitPostingByTxnId(existing.getTxnId());

        boolean shapeMatches =
                credit != null && debit != null
                && credit.getAccountId() == toAccountId
                && debit.getAccountId() == fromAccountId
                && credit.getAmount().compareTo(amount) == 0
                && java.util.Objects.equals(
                        existing.getMessage() == null ? "" : existing.getMessage(),
                        memo == null ? "" : memo);

        if (!shapeMatches) {
            log.warn("Idempotency conflict: txnId={} stored from={} to={} amount={} memo='{}' "
                    + "vs requested from={} to={} amount={} memo='{}'",
                    existing.getTxnId(),
                    debit  != null ? debit.getAccountId()  : "null",
                    credit != null ? credit.getAccountId() : "null",
                    credit != null ? credit.getAmount()    : "null",
                    existing.getMessage(),
                    fromAccountId, toAccountId, amount, memo);
            throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                    "Idempotency key reused with a different request body.");
        }

        log.info("Replaying idempotent transfer: txnId={} fromAccount={} toAccount={} amount={}",
                existing.getTxnId(), debit.getAccountId(), credit.getAccountId(),
                credit.getAmount().toPlainString());

        // Echo the *stored* values so the caller can't be misled into believing
        // their request was honoured when the original txn carried different
        // fields.
        return new TransferResponse(
                existing.getTxnId(),
                debit.getAccountId(),
                credit.getAccountId(),
                credit.getAmount().toPlainString(),
                existing.getMessage(),
                existing.getSettlementTime().toInstant(ZoneOffset.UTC)
        );
    }

    /**
     * Parses and bounds the client-supplied amount string.
     *
     * <p>Security-critical: a {@link BigDecimal} built from attacker input must be
     * bounded <em>before</em> any arithmetic touches it. {@code "1E1000000000"} is
     * cheap to construct (unscaled 1, scale −1e9) and slips past a naive {@code > 0}
     * check (which only inspects the sign), but the overdraft {@code subtract} and the
     * {@code toPlainString}/JDBC-serialise paths rescale it into a ~1e9-digit
     * BigInteger/String, exhausting the heap — a one-request OOM DoS. So we cap the raw
     * string length (parse cost), then the scale and integer-digit count to the
     * DECIMAL(19,2) ledger column, rejecting anything out of range as a clean 400.
     */
    private static BigDecimal validatedAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            log.warn("Transfer rejected: missing amount");
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "Field 'amount' is required.");
        }
        if (raw.length() > MAX_AMOUNT_STRING_LENGTH) {
            log.warn("Transfer rejected: amount string length {} exceeds {}",
                    raw.length(), MAX_AMOUNT_STRING_LENGTH);
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "Field 'amount' is too long.");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(raw);
        } catch (NumberFormatException e) {
            log.warn("Transfer rejected: unparseable amount='{}'", raw);
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "Field 'amount' must be a valid decimal number.");
        }
        if (amount.signum() <= 0) {
            log.warn("Transfer rejected: non-positive amount={}", amount);
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "Field 'amount' must be greater than zero.");
        }
        // Normalise away trailing zeros so "10.000" is accepted, then bound scale and
        // magnitude to DECIMAL(19,2). stripTrailingZeros is cheap here (unscaled value
        // is tiny for the dangerous scientific-notation inputs), and crucially runs
        // before any rescaling arithmetic.
        BigDecimal normalized = amount.stripTrailingZeros();
        if (normalized.scale() > MAX_AMOUNT_SCALE) {
            log.warn("Transfer rejected: amount {} has more than {} decimal places", raw, MAX_AMOUNT_SCALE);
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "Field 'amount' must have at most " + MAX_AMOUNT_SCALE + " decimal places.");
        }
        if ((long) normalized.precision() - normalized.scale() > MAX_AMOUNT_INTEGER_DIGITS) {
            log.warn("Transfer rejected: amount {} exceeds DECIMAL(19,2) range", raw);
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "Field 'amount' exceeds the maximum supported value.");
        }
        return amount;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
