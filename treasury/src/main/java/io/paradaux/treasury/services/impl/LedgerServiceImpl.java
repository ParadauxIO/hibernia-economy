package io.paradaux.treasury.services.impl;

import com.google.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.common.OverdraftPolicy;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.mappers.LedgerMapper;
import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.config.EconomyConfiguration;
import io.paradaux.treasury.model.config.GovernmentConfiguration;
import io.paradaux.treasury.model.economy.*;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.MembershipService;
import io.paradaux.treasury.utils.AccountFactory;
import io.paradaux.treasury.utils.Idempotency;
import io.paradaux.treasury.utils.Money;
import io.paradaux.treasury.services.cache.PersonalAccountCache;
import io.paradaux.treasury.services.cache.PluginSystemAccountCache;
import io.paradaux.treasury.utils.TreasuryConstants;
import org.apache.ibatis.exceptions.PersistenceException;
import org.jetbrains.annotations.Nullable;
import org.mybatis.guice.transactional.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
public class LedgerServiceImpl implements LedgerService {

    private final AccountMapper accountMapper;
    private final MembershipService membershipService;
    private final LedgerMapper ledgerMapper;
    private final AccountService accountService;
    private final PluginSystemAccountCache systemAccountCache;
    private final PersonalAccountCache personalAccountCache;
    private final EconomyConfiguration economyConfig;
    private final GovernmentConfiguration govConfig;

    @Inject
    public LedgerServiceImpl(AccountMapper accountMapper,
                             MembershipService membershipService,
                             LedgerMapper ledgerMapper,
                             AccountService accountService,
                             PluginSystemAccountCache cache,
                             PersonalAccountCache personalAccountCache,
                             EconomyConfiguration economyConfig,
                             GovernmentConfiguration govConfig) {
        this.accountMapper        = accountMapper;
        this.membershipService    = membershipService;
        this.ledgerMapper         = ledgerMapper;
        this.accountService       = accountService;
        this.systemAccountCache   = cache;
        this.personalAccountCache = personalAccountCache;
        this.economyConfig        = economyConfig;
        this.govConfig            = govConfig;
    }

    // ---- Startup ----

    @Override
    @Transactional
    public void bootstrapGovernmentAccounts() {
        ensurePrimitiveGovernmentAccount(govConfig.getStartingBalancesAccount());
        ensurePrimitiveGovernmentAccount(govConfig.getTaxIncomeAccount());
        ensurePrimitiveGovernmentAccount(govConfig.getFinesAccount());
    }

    /**
     * Ensures a primitive GOVERNMENT account exists with the unlimited-credit
     * sentinel ({@code credit_limit = -1}). Forward-migrates legacy rows that
     * pre-date this convention.
     */
    private void ensurePrimitiveGovernmentAccount(String name) {
        Account existing = getOrCreateGovernmentAccount(name);
        if (existing.getCreditLimit() == null
                || existing.getCreditLimit().signum() >= 0) {
            existing.setCreditLimit(BigDecimal.valueOf(-1));
            accountMapper.updateAccount(existing);
            log.info("Migrated GOVERNMENT account '{}' (id={}) to credit_limit=-1 (unlimited)",
                    name, existing.getAccountId());
        }
    }

    // ---- Player account lifecycle ----

    @Override
    @Transactional
    public Account resolveOrCreatePersonal(UUID playerUuid) {
        Integer existingId = accountMapper.findPersonalAccountId(playerUuid);
        if (existingId != null) {
            AccountBalance ab = accountMapper.readBalance(existingId);
            if (ab != null && ab.getBalance() != null && ab.getBalance().signum() > 0) {
                // Funded account — already onboarded. Fast path for the hot Vault/
                // ChestShop callers.
                personalAccountCache.put(playerUuid, existingId);
                return accountMapper.findById(existingId);
            }
            if (ab == null) {
                accountMapper.seedBalance(existingId);
            }
            // Zero balance: the account may have been created by another path
            // (salary/Vault/ChestShop) that seeded only the balance row, not the
            // starting balance — previously this returned early and the new player
            // permanently lost their starting balance. Fall through to the seed;
            // the dedup key below makes it a no-op if it was already applied (or
            // the player legitimately spent down to zero).
        }

        int newPersonalId = accountService.getOrCreatePersonalAccountId(playerUuid);
        Account personal = accountMapper.findById(newPersonalId);

        Account startingGov = getOrCreateGovernmentAccount(govConfig.getStartingBalancesAccount());
        BigDecimal startingBalance = economyConfig.getStartingBalance();
        byte[] dedup = Idempotency.sha256("starting-balance:" + playerUuid);
        TransferRequest seedReq = new TransferRequest(
                startingGov.getAccountId(),
                personal.getAccountId(),
                startingBalance,
                "Initial player funds",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                null,
                TreasuryConstants.TREASURY_PLUGIN_NAME,
                dedup
        );
        transfer(seedReq);

        return personal;
    }

    // ---- Core transfer (double-entry) ----

    @Override
    @Transactional
    public long transfer(TransferRequest req) {
        return transferInternal(req, false).txnId();
    }

    @Override
    @Transactional
    public long adminTransfer(TransferRequest req) {
        return transferInternal(req, true).txnId();
    }

    @Override
    @Transactional
    public TransferResult transferChecked(TransferRequest req) {
        return transferInternal(req, false);
    }

    private TransferResult transferInternal(TransferRequest req, boolean bypassAuthorization) {
        Objects.requireNonNull(req, "transfer request");
        Money.requirePositive(req.amount(), "amount > 0");

        // A same-account transfer is a net-zero no-op with unspecified semantics
        // (it would post +x and -x to one balance row). Reject it up front rather
        // than letting it write a meaningless pair of postings (ADT-33).
        if (req.fromAccountId() == req.toAccountId()) {
            throw new IllegalArgumentException("Cannot transfer to the same account (id=" + req.fromAccountId() + ")");
        }

        if (req.dedupKey() != null) {
            LedgerTxn existing = ledgerMapper.findByDedupKey(req.dedupKey());
            if (existing != null) return new TransferResult(existing.getTxnId(), false);
        }

        // Load both account rows in a single round-trip.
        int fromId = req.fromAccountId();
        int toId   = req.toAccountId();
        Account from = null;
        Account to   = null;
        for (Account a : accountMapper.findByIds(List.of(fromId, toId))) {
            if (a.getAccountId() == fromId) from = a;
            if (a.getAccountId() == toId)   to = a;
        }
        if (from == null || to == null) {
            throw new IllegalArgumentException("Account not found (from=" + fromId + ", to=" + toId + ")");
        }

        if (!bypassAuthorization && (from.isRequiresAuthorization() || to.isRequiresAuthorization())) {
            UUID auth = req.authorizer();
            if (auth == null) throw new SecurityException("Authorizer required");
            if (from.isRequiresAuthorization()
                    && !membershipService.isAuthorizer(from.getAccountId(), auth)) {
                throw new SecurityException("Authorizer not permitted on source account");
            }
            if (to.isRequiresAuthorization()
                    && !membershipService.isAuthorizer(to.getAccountId(), auth)) {
                throw new SecurityException("Authorizer not permitted on destination account");
            }
        }

        // The insufficient-funds / overdraft invariant applies only to the SOURCE, and
        // only when it is limited. Overdraft semantics:
        //   allow_overdraft = false                  → balance must stay ≥ 0
        //   allow_overdraft = true,  credit_limit ≥ 0 → balance ≥ -credit_limit
        //   allow_overdraft = true,  credit_limit < 0 → unlimited (system faucet/sink)
        // When a check is required we lock BOTH balance rows FOR UPDATE in ascending
        // account_id order (one round-trip) so the read is serialised and lock
        // acquisition is globally ordered. Unlimited-source transfers need no check, so
        // we skip the explicit lock and let the AFTER-INSERT posting trigger take the
        // (shorter-held) row locks at insert time — cutting hold time on shared
        // GOVERNMENT/SYSTEM accounts. Deadlock-freedom holds because the posting inserts
        // below are also ascending-ordered, so every path acquires low-id before high-id.
        // Read the SOURCE overdraft flags under a shared row lock rather than from
        // the unlocked findByIds snapshot above. Otherwise a concurrent flip of
        // allow_overdraft / credit_limit (in a separate txn via updateAccount) could
        // be missed, and a now-limited account would be treated as unlimited and
        // overdraw (ADT-10). The shared lock excludes only the (rare) exclusive flag
        // flip, so concurrent transfers from a shared faucet still run in parallel.
        // (The treasury-rest-api engine deliberately does NOT take this lock — it has no
        // path that writes these flags, so the race can't arise there. Keep the two in
        // sync only if that ever changes; see TransferService's parity note.)
        Account lockedFrom = accountMapper.lockAccountFlagsForShare(fromId);
        if (lockedFrom == null) {
            throw new IllegalArgumentException("Account not found (from=" + fromId + ")");
        }
        // Unlimited sources (SYSTEM faucet/sinks, or the credit_limit=-1 sentinel) need no
        // balance check and thus no balance lock; any limited source is checked against the
        // shared OverdraftPolicy floor so this engine and treasury-rest-api decide identically
        // (PAR-319 overdraft-parity).
        boolean sourceIsSystem = lockedFrom.getAccountType() == AccountType.SYSTEM;
        if (!OverdraftPolicy.isUnlimited(lockedFrom.isAllowOverdraft(), lockedFrom.getCreditLimit(), sourceIsSystem)) {
            AccountBalance fromBal = null;
            for (AccountBalance b : accountMapper.lockBalances(List.of(fromId, toId))) {
                if (b.getAccountId() == fromId) { fromBal = b; break; }
            }
            if (fromBal == null) {
                throw new IllegalStateException("Missing balance row for source account " + fromId);
            }
            if (!OverdraftPolicy.isWithinFloor(fromBal.getBalance(), Money.normalize(req.amount()),
                    lockedFrom.isAllowOverdraft(), lockedFrom.getCreditLimit(), sourceIsSystem)) {
                throw new IllegalStateException("Insufficient funds");
            }
        }

        final long txnId;
        try {
            txnId = insertTxn(req.message(), req.initiator(), req.authorizer(),
                    req.pluginSystem(), req.dedupKey());
        } catch (PersistenceException e) {
            // Concurrent identical-dedup-key transfer: another thread/process inserted
            // a ledger_txn with this client_dedup_key between our findByDedupKey check
            // above and this insert, tripping uq_ledger_dedup. The unique constraint
            // already prevented the double-spend; re-resolve to the committed txn id
            // (locking read so REPEATABLE READ sees it) and return it instead of
            // propagating the duplicate-key error (ADT-73, mirrors the *Locking
            // re-resolves in AccountServiceImpl, ADT-74).
            if (req.dedupKey() != null) {
                LedgerTxn raced = ledgerMapper.findByDedupKeyLocking(req.dedupKey());
                if (raced != null) {
                    log.debug("Transfer deduplicated on concurrent insert: returning existing txn={}", raced.getTxnId());
                    return new TransferResult(raced.getTxnId(), false);
                }
            }
            throw e;
        }

        postDoubleEntry(txnId, fromId, toId, req.amount(), req.message());
        log.debug("Transfer txn={} from={} to={} amount={} plugin={}",
                txnId, fromId, toId, req.amount(), req.pluginSystem());
        return new TransferResult(txnId, true);
    }

    @Override
    @Transactional
    public java.util.OptionalLong sweepAll(int fromAccountId, int toAccountId, String memo, UUID initiator, String sourcePlugin) {
        Objects.requireNonNull(memo, "memo");
        Objects.requireNonNull(initiator, "initiator");
        if (fromAccountId == toAccountId) {
            throw new IllegalArgumentException("Cannot sweep to the same account (id=" + fromAccountId + ")");
        }

        // Lock BOTH balance rows FOR UPDATE in ascending account_id order — identical
        // ordering to transferInternal's lock and to the ascending posting inserts below
        // — so a sweep and a concurrent A→B / B→A transfer can never deadlock. The source
        // amount is read from this freshly-locked row, not a stale account_balances_mat
        // snapshot, so a credit landing after the caller's snapshot is included and a
        // debit can't make the sweep overdraw (conservation stays exact).
        int firstId  = Math.min(fromAccountId, toAccountId);
        int secondId = Math.max(fromAccountId, toAccountId);
        AccountBalance sourceBalance = null;
        for (AccountBalance b : accountMapper.lockBalances(List.of(firstId, secondId))) {
            if (b.getAccountId() == fromAccountId) { sourceBalance = b; break; }
        }
        if (sourceBalance == null) {
            throw new IllegalStateException("Missing balance row for source account " + fromAccountId);
        }

        BigDecimal amount = sourceBalance.getBalance();
        if (amount == null || amount.signum() <= 0) {
            log.debug("Sweep no-op: locked balance on account {} is {} (nothing to move)", fromAccountId, amount);
            return java.util.OptionalLong.empty();
        }

        long txnId = insertTxn(memo, initiator, null, sourcePlugin, null);
        postDoubleEntry(txnId, fromAccountId, toAccountId, amount, memo);
        log.debug("Swept locked balance {} from account {} to {} (txn={})",
                amount, fromAccountId, toAccountId, txnId);
        return java.util.OptionalLong.of(txnId);
    }

    /**
     * Emits the two balanced postings (debit source, credit destination) for a settled
     * amount, ascending account_id so the per-row balance trigger acquires balance-row
     * locks in a consistent global order. The {@code trg_postings_ai} trigger — never an
     * application UPDATE — applies the {@code account_balances_mat} deltas. Shared by
     * {@link #transferInternal} and {@link #sweepAll} so both write the ledger identically.
     */
    private void postDoubleEntry(long txnId, int fromId, int toId, BigDecimal amount, String message) {
        LedgerPosting fromPosting = new LedgerPosting(null, txnId, fromId, amount.negate(), message);
        LedgerPosting toPosting   = new LedgerPosting(null, txnId, toId,   amount,          message);
        ledgerMapper.insertPostings(fromId <= toId
                ? List.of(fromPosting, toPosting)
                : List.of(toPosting, fromPosting));
    }

    // ---- Vault compatibility ----

    @Override
    @Transactional
    public long vaultWithdraw(String pluginName, UUID playerUuid, BigDecimal amount, String memo,
                              UUID initiator, @Nullable UUID authorizer,
                              @Nullable String idempotencyKey) {
        Money.requirePositive(amount, "amount > 0");
        int systemId = systemAccountCache.getOrCreate(pluginName, initiator);
        int playerAccountId = resolveVaultAccountId(playerUuid);
        byte[] dedup = idempotencyKey == null ? null : Idempotency.sha256(idempotencyKey);
        return transfer(new TransferRequest(
                playerAccountId, systemId, amount, memo,
                initiator, authorizer, pluginName, dedup));
    }

    @Override
    @Transactional
    public long vaultDeposit(String pluginName, UUID playerUuid, BigDecimal amount, String memo,
                             UUID initiator, @Nullable UUID authorizer,
                             @Nullable String idempotencyKey) {
        Money.requirePositive(amount, "amount > 0");
        int systemId = systemAccountCache.getOrCreate(pluginName, initiator);
        int playerAccountId = resolveVaultAccountId(playerUuid);
        byte[] dedup = idempotencyKey == null ? null : Idempotency.sha256(idempotencyKey);
        return transfer(new TransferRequest(
                systemId, playerAccountId, amount, memo,
                initiator, authorizer, pluginName, dedup));
    }

    /**
     * Resolves the account_id a Vault call should target for a UUID:
     * an {@code account_redirects} entry takes precedence (used to
     * route legacy "player" UUIDs like DCGovernment / GovReserve onto
     * their canonical GOVERNMENT accounts), falling through to the
     * usual PERSONAL resolve-or-create path otherwise.
     *
     * <p>Native callers don't go through this — they target accounts
     * by ID directly via {@link #transfer(TransferRequest)}.
     */
    private int resolveVaultAccountId(UUID playerUuid) {
        Optional<Integer> redirected = accountService.findRedirectedAccount(playerUuid);
        if (redirected.isPresent()) return redirected.get();
        Integer cached = personalAccountCache.get(playerUuid);
        if (cached != null) return cached;
        // Miss: resolve (creating + seeding starting balance if needed).
        // resolveOrCreatePersonal caches the id only when it reads an existing,
        // committed account — never a freshly created (possibly-rolled-back) one.
        return resolveOrCreatePersonal(playerUuid).getAccountId();
    }

    // ---- Admin economy operations ----

    @Override
    @Transactional
    public long adminGive(UUID playerUuid, BigDecimal amount, String memo, UUID adminUuid) {
        Money.requirePositive(amount, "amount > 0");
        Account ecoAccount = getOrCreateGovernmentAccount(TreasuryConstants.ECO_ACCOUNT_NAME);
        Account player = resolveOrCreatePersonal(playerUuid);
        long txnId = transfer(new TransferRequest(
                ecoAccount.getAccountId(), player.getAccountId(),
                amount, memo, adminUuid, null, TreasuryConstants.ECO_PLUGIN_SYSTEM, null));
        log.info("Admin give: admin={} player={} amount={} txn={}", adminUuid, playerUuid, amount, txnId);
        return txnId;
    }

    @Override
    @Transactional
    public long adminTake(UUID playerUuid, BigDecimal amount, String memo, UUID adminUuid) {
        Money.requirePositive(amount, "amount > 0");
        Account ecoAccount = getOrCreateGovernmentAccount(TreasuryConstants.ECO_ACCOUNT_NAME);
        Account player = resolveOrCreatePersonal(playerUuid);
        long txnId = transfer(new TransferRequest(
                player.getAccountId(), ecoAccount.getAccountId(),
                amount, memo, adminUuid, null, TreasuryConstants.ECO_PLUGIN_SYSTEM, null));
        log.info("Admin take: admin={} player={} amount={} txn={}", adminUuid, playerUuid, amount, txnId);
        return txnId;
    }

    @Override
    @Transactional
    public Optional<Long> adminSet(UUID playerUuid, BigDecimal targetAmount, String memo, UUID adminUuid) {
        Account player = resolveOrCreatePersonal(playerUuid);
        // Lock the balance row (FOR UPDATE) before reading it: adminSet is a
        // read-modify-write (compute delta = target - current) and the follow-up
        // give/take runs in this same transaction, so the lock is held across both.
        // An unlocked read let two concurrent `/eco set` land the wrong final
        // balance (ADT-33).
        AccountBalance bal = accountMapper.lockBalance(player.getAccountId());
        BigDecimal current = bal == null ? BigDecimal.ZERO : bal.getBalance();
        BigDecimal delta = targetAmount.subtract(current);
        if (delta.signum() == 0) return Optional.empty();
        if (delta.signum() > 0) {
            return Optional.of(adminGive(playerUuid, delta, memo, adminUuid));
        } else {
            return Optional.of(adminTake(playerUuid, delta.abs(), memo, adminUuid));
        }
    }

    @Override
    @Transactional
    public void adminReset(UUID playerUuid, UUID adminUuid) {
        BigDecimal starting = Money.normalize(economyConfig.getStartingBalance());
        adminSet(playerUuid, starting, "Admin reset balance", adminUuid);
    }

    // ---- Transaction history ----

    @Override
    @Transactional
    public Page<TransactionEntry> getTransactionHistory(int accountId, int offset, int limit) {
        List<TransactionEntry> items = ledgerMapper.findTransactionsByAccount(accountId, limit, offset);
        int total = ledgerMapper.countTransactionsByAccount(accountId);
        return new Page<>(items, total, offset, limit);
    }

    @Override
    @Transactional
    public Page<TransactionEntry> getTransactionHistory(Collection<Integer> accountIds, int offset, int limit) {
        if (accountIds == null || accountIds.isEmpty()) {
            return new Page<>(List.of(), 0, offset, limit);
        }
        List<Integer> ids = new ArrayList<>(accountIds);
        List<TransactionEntry> items = ledgerMapper.findTransactionsByAccounts(ids, limit, offset);
        int total = ledgerMapper.countTransactionsByAccounts(ids);
        return new Page<>(items, total, offset, limit);
    }

    @Override
    @Transactional
    public LedgerTxn getTransaction(long txnId) {
        return ledgerMapper.findTxnById(txnId);
    }

    @Override
    @Transactional
    public List<LedgerPosting> getPostingsForTransaction(long txnId) {
        return ledgerMapper.findPostingsByTxnId(txnId);
    }

    // ---- Private helpers ----

    private long insertTxn(String message, UUID initiator, @Nullable UUID authorizer,
                           @Nullable String pluginSystem, byte @Nullable [] dedup) {
        LedgerTxn txn = new LedgerTxn();
        txn.setMessage(message);
        txn.setInitiatorUuid(initiator);
        txn.setAuthorizerUuid(authorizer);
        txn.setPluginSystem(pluginSystem);
        txn.setClientDedupKey(dedup);
        ledgerMapper.insertTxnEntity(txn);
        return txn.getTxnId();
    }

    private Account getOrCreateGovernmentAccount(String name) {
        Account existing = accountMapper.findGovernmentAccountByName(name);
        if (existing != null) {
            if (accountMapper.readBalance(existing.getAccountId()) == null) {
                accountMapper.seedBalance(existing.getAccountId());
                log.warn("Government account '{}' (id={}) was missing its balance row — re-seeded",
                        name, existing.getAccountId());
            }
            return existing;
        }

        // Primitive GOVERNMENT accounts (starting-balances, tax-income, fines, Eco)
        // are system faucets/sinks; the -1 sentinel disables the credit-limit check.
        Account account = AccountFactory.primitiveGovernment(name);
        accountMapper.insertAccount(account);
        accountMapper.seedBalance(account.getAccountId());
        log.info("Created GOVERNMENT account '{}' (id={})", name, account.getAccountId());
        return account;
    }
}
