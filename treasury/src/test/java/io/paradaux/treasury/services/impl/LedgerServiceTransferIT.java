package io.paradaux.treasury.services.impl;

import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.mappers.LedgerMapper;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountBalance;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.LedgerPosting;
import io.paradaux.treasury.model.economy.LedgerTxn;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.MembershipService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import io.paradaux.treasury.utils.Idempotency;
import io.paradaux.treasury.utils.TreasuryConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerServiceTransferIT extends IntegrationTestBase {

    private LedgerService ledgerService;
    private AccountService accountService;
    private MembershipService membershipService;
    private AccountMapper accountMapper;
    private LedgerMapper ledgerMapper;

    @BeforeEach
    void setUp() {
        ledgerService     = injector.getInstance(LedgerService.class);
        accountService    = injector.getInstance(AccountService.class);
        membershipService = injector.getInstance(MembershipService.class);
        accountMapper     = injector.getInstance(AccountMapper.class);
        ledgerMapper      = injector.getInstance(LedgerMapper.class);

        ledgerService.bootstrapGovernmentAccounts();
    }

    // ---------- transfer ----------

    @Test
    void transfer_happyPath_createsBalancedPostingsAndUpdatesBalances() {
        Account a = createPersonalAccount(1_000);
        Account b = createPersonalAccount(0);

        long txnId = ledgerService.transfer(new TransferRequest(
                a.getAccountId(), b.getAccountId(),
                new BigDecimal("250.00"), "test transfer",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null,
                TreasuryConstants.TREASURY_PLUGIN_NAME, null));

        assertThat(balanceOf(a)).isEqualByComparingTo("750.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("250.00");

        List<LedgerPosting> postings = ledgerMapper.findPostingsByTxnId(txnId);
        assertThat(postings).hasSize(2);
        BigDecimal sum = postings.stream()
                .map(LedgerPosting::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void transfer_withDedupKey_returnsExistingTxnOnReplay() {
        Account a = createPersonalAccount(500);
        Account b = createPersonalAccount(0);
        byte[] key = Idempotency.sha256("test:dedup-key");

        long firstTxn = ledgerService.transfer(new TransferRequest(
                a.getAccountId(), b.getAccountId(), new BigDecimal("100.00"),
                "first", TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null, "test", key));

        long secondTxn = ledgerService.transfer(new TransferRequest(
                a.getAccountId(), b.getAccountId(), new BigDecimal("100.00"),
                "replay", TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null, "test", key));

        assertThat(secondTxn).isEqualTo(firstTxn);
        assertThat(balanceOf(a)).isEqualByComparingTo("400.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("100.00");
    }

    @Test
    void transfer_concurrentIdenticalDedupKey_allReturnSameTxnAndChargeOnce() throws Exception {
        // ADT-73: several threads fire the SAME logical transfer (identical dedup key)
        // at once. uq_ledger_dedup guarantees the money moves once; the loser inserts
        // must re-resolve to the winner's txn id rather than propagating a duplicate-key
        // PersistenceException. Assert: no thread throws, all return one txn id, the
        // source is debited exactly once, and exactly one ledger_txn row exists.
        Account a = createPersonalAccount(1_000);
        Account b = createPersonalAccount(0);
        byte[] key = Idempotency.sha256("test:concurrent-dedup");

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Long>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    start.await(); // line everyone up so the inserts genuinely race
                    return ledgerService.transfer(new TransferRequest(
                            a.getAccountId(), b.getAccountId(), new BigDecimal("100.00"),
                            "concurrent", TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null, "test", key));
                });
            }
            List<Future<Long>> futures = new java.util.ArrayList<>();
            for (Callable<Long> t : tasks) futures.add(pool.submit(t));
            start.countDown();

            Long firstTxn = null;
            for (Future<Long> f : futures) {
                Long txn = f.get(30, TimeUnit.SECONDS); // throws if any thread propagated an exception
                if (firstTxn == null) firstTxn = txn;
                assertThat(txn).isEqualTo(firstTxn);
            }
        } finally {
            pool.shutdownNow();
        }

        // Money moved exactly once despite N concurrent attempts.
        assertThat(balanceOf(a)).isEqualByComparingTo("900.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("100.00");
        assertThat(ledgerMapper.findByDedupKey(key)).isNotNull();
    }

    @Test
    void transfer_missingAccount_throws() {
        Account a = createPersonalAccount(100);

        assertThatThrownBy(() -> ledgerService.transfer(new TransferRequest(
                a.getAccountId(), 9999, new BigDecimal("10.00"), "missing dest",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null, "test", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account not found");
    }

    @Test
    void transfer_insufficientFundsWithoutOverdraft_throws() {
        Account a = createPersonalAccount(50);
        Account b = createPersonalAccount(0);

        assertThatThrownBy(() -> ledgerService.transfer(new TransferRequest(
                a.getAccountId(), b.getAccountId(), new BigDecimal("100.00"), "overdraw",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null, "test", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient funds");

        assertThat(balanceOf(a)).isEqualByComparingTo("50.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("0.00");
    }

    @Test
    void transfer_overdraftWithinCreditLimit_succeeds() {
        Account a = createOverdraftAccount(50, new BigDecimal("100.00"));
        Account b = createPersonalAccount(0);

        ledgerService.transfer(new TransferRequest(
                a.getAccountId(), b.getAccountId(), new BigDecimal("120.00"), "overdraft",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null, "test", null));

        // 50 - 120 = -70, within credit limit of 100
        assertThat(balanceOf(a)).isEqualByComparingTo("-70.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("120.00");
    }

    @Test
    void transfer_overdraftBeyondCreditLimit_throws() {
        Account a = createOverdraftAccount(50, new BigDecimal("100.00"));
        Account b = createPersonalAccount(0);

        // 50 - 200 = -150, exceeds the -100 credit limit
        assertThatThrownBy(() -> ledgerService.transfer(new TransferRequest(
                a.getAccountId(), b.getAccountId(), new BigDecimal("200.00"), "too far",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null, "test", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient funds");

        // No money moved
        assertThat(balanceOf(a)).isEqualByComparingTo("50.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("0.00");
    }

    @Test
    void transfer_negativeCreditLimit_meansUnlimitedOverdraft() {
        // credit_limit = -1 is the sentinel for system faucet/sink accounts (Vault bridge,
        // primitive government). They can go arbitrarily negative.
        Account faucet = createOverdraftAccount(0, new BigDecimal("-1"));
        Account b = createPersonalAccount(0);

        ledgerService.transfer(new TransferRequest(
                faucet.getAccountId(), b.getAccountId(), new BigDecimal("1000000.00"),
                "minted from faucet",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null, "test", null));

        assertThat(balanceOf(faucet)).isEqualByComparingTo("-1000000.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("1000000.00");
    }

    @Test
    void bootstrapGovernmentAccounts_setsPrimitivesToUnlimitedCredit() {
        // The forward-migration step in bootstrap should ensure every primitive
        // GOVERNMENT account is at credit_limit = -1, even if a legacy install
        // had it at 0 or some positive value.
        Account starting = accountService.getGovernmentAccountByName("starting-balances");
        Account taxIncome = accountService.getGovernmentAccountByName("DCGovernment");
        Account fines = accountService.getGovernmentAccountByName("GovernmentFines");

        assertThat(starting.getCreditLimit()).isEqualByComparingTo("-1");
        assertThat(taxIncome.getCreditLimit()).isEqualByComparingTo("-1");
        assertThat(fines.getCreditLimit()).isEqualByComparingTo("-1");
    }

    @Test
    void transfer_authorizerRequired_butAbsent_throws() {
        Account a = createAuthorizationRequiredAccount(1000);
        Account b = createPersonalAccount(0);

        assertThatThrownBy(() -> ledgerService.transfer(new TransferRequest(
                a.getAccountId(), b.getAccountId(), new BigDecimal("10.00"), "no auth",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null /* no authorizer */,
                "test", null)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Authorizer required");
    }

    @Test
    void adminTransfer_bypassesAuthorizationGate() {
        Account a = createAuthorizationRequiredAccount(1000);
        Account b = createPersonalAccount(0);

        // transfer() refuses without an authorizer...
        assertThatThrownBy(() -> ledgerService.transfer(new TransferRequest(
                a.getAccountId(), b.getAccountId(), new BigDecimal("10.00"), "blocked",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null, "test", null)))
                .isInstanceOf(SecurityException.class);

        // ...adminTransfer() forces it through (the permission is the authority).
        long txnId = ledgerService.adminTransfer(new TransferRequest(
                a.getAccountId(), b.getAccountId(), new BigDecimal("10.00"), "admin override",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null, "test", null));

        assertThat(txnId).isPositive();
        assertThat(balanceOf(a)).isEqualByComparingTo("990.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("10.00");
    }

    @Test
    void adminTransfer_stillEnforcesInsufficientFunds() {
        Account a = createPersonalAccount(5);   // no overdraft
        Account b = createPersonalAccount(0);

        assertThatThrownBy(() -> ledgerService.adminTransfer(new TransferRequest(
                a.getAccountId(), b.getAccountId(), new BigDecimal("10.00"), "too much",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null, "test", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insufficient funds");
    }

    @Test
    void transfer_authorizerRequired_andPermitted_succeeds() {
        Account a = createAuthorizationRequiredAccount(1000);
        Account b = createPersonalAccount(0);
        UUID authorizer = UUID.randomUUID();

        // The authorizer must already be a member before becoming an authorizer.
        membershipService.addMember(a.getAccountId(), authorizer, TreasuryConstants.VIRTUAL_TREASURY_INITIATOR);
        membershipService.addAuthorizer(a.getAccountId(), authorizer, TreasuryConstants.VIRTUAL_TREASURY_INITIATOR);

        ledgerService.transfer(new TransferRequest(
                a.getAccountId(), b.getAccountId(), new BigDecimal("100.00"), "ok",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, authorizer,
                "test", null));

        assertThat(balanceOf(a)).isEqualByComparingTo("900.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("100.00");
    }

    @Test
    void transfer_authorizerRequired_butNotPermitted_throws() {
        Account a = createAuthorizationRequiredAccount(1000);
        Account b = createPersonalAccount(0);
        UUID notAuthorized = UUID.randomUUID();

        assertThatThrownBy(() -> ledgerService.transfer(new TransferRequest(
                a.getAccountId(), b.getAccountId(), new BigDecimal("10.00"), "rogue",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, notAuthorized,
                "test", null)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not permitted");
    }

    @Test
    void transfer_writesTxnHeaderWithInitiatorAndPlugin() {
        Account a = createPersonalAccount(500);
        Account b = createPersonalAccount(0);
        UUID initiator = UUID.randomUUID();

        long txnId = ledgerService.transfer(new TransferRequest(
                a.getAccountId(), b.getAccountId(), new BigDecimal("10.00"), "memo here",
                initiator, null, "MyPlugin", null));

        LedgerTxn txn = ledgerMapper.findTxnById(txnId);
        assertThat(txn).isNotNull();
        assertThat(txn.getMessage()).isEqualTo("memo here");
        assertThat(txn.getInitiatorUuid()).isEqualTo(initiator);
        assertThat(txn.getPluginSystem()).isEqualTo("MyPlugin");
    }

    // ---------- adminGive / adminTake / adminSet ----------

    @Test
    void adminGive_increasesBalanceByAmount() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player); // seeds 10 000

        ledgerService.adminGive(player, new BigDecimal("250.00"), "bonus", UUID.randomUUID());

        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("10250.00");
    }

    @Test
    void adminTake_decreasesBalanceByAmount() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        ledgerService.adminTake(player, new BigDecimal("1000.00"), "fine", UUID.randomUUID());

        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("9000.00");
    }

    @Test
    void adminSet_higherThanCurrent_givesDelta() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        var txn = ledgerService.adminSet(player, new BigDecimal("12500.00"), "set up", UUID.randomUUID());

        assertThat(txn).isPresent();
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("12500.00");
    }

    @Test
    void adminSet_lowerThanCurrent_takesDelta() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        var txn = ledgerService.adminSet(player, new BigDecimal("4000.00"), "set down", UUID.randomUUID());

        assertThat(txn).isPresent();
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("4000.00");
    }

    @Test
    void adminSet_equalToCurrent_isNoOp() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        BigDecimal current = accountService.getBalanceByOwnerUuid(player);

        var txn = ledgerService.adminSet(player, current, "no change", UUID.randomUUID());

        assertThat(txn).isEmpty();
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo(current);
    }

    // ---------- resolveOrCreatePersonal ----------

    @Test
    void resolveOrCreatePersonal_firstCall_createsAccountAndSeedsStartingBalance() {
        UUID player = UUID.randomUUID();

        Account account = ledgerService.resolveOrCreatePersonal(player);

        assertThat(account.getAccountType()).isEqualTo(AccountType.PERSONAL);
        assertThat(account.getOwnerUuid()).isEqualTo(player);
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("10000.00");
    }

    @Test
    void resolveOrCreatePersonal_secondCall_doesNotDoubleFund() {
        UUID player = UUID.randomUUID();

        ledgerService.resolveOrCreatePersonal(player);
        ledgerService.resolveOrCreatePersonal(player);

        // Dedup key on starting-balance transfer prevents a second seed.
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("10000.00");
    }

    // ---------- bootstrapGovernmentAccounts ----------

    @Test
    void bootstrapGovernmentAccounts_isIdempotent() {
        // Was called once in @BeforeEach. Calling again shouldn't error or duplicate.
        ledgerService.bootstrapGovernmentAccounts();
        ledgerService.bootstrapGovernmentAccounts();

        assertThat(accountService.getGovernmentAccountByName("starting-balances")).isNotNull();
        assertThat(accountService.getGovernmentAccountByName("DCGovernment")).isNotNull();
        assertThat(accountService.getGovernmentAccountByName("GovernmentFines")).isNotNull();
    }

    @Test
    void bootstrap_migratesLegacyCreditLimitToMinusOne() {
        // Roll a primitive back to a legacy credit_limit then re-bootstrap.
        Account starting = accountService.getGovernmentAccountByName("starting-balances");
        starting.setCreditLimit(BigDecimal.ZERO);
        accountService.updateAccount(starting);

        ledgerService.bootstrapGovernmentAccounts();

        Account migrated = accountService.getGovernmentAccountByName("starting-balances");
        assertThat(migrated.getCreditLimit()).isEqualByComparingTo("-1");
    }

    // ---------- vault adapter pass-throughs ----------

    @Test
    void vaultDeposit_mintsFromSystemAccount() {
        UUID player = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        long txnId = ledgerService.vaultDeposit("MyPlugin", player, new BigDecimal("250.00"),
                "deposit memo", initiator, null, "deposit-key-1");

        assertThat(txnId).isPositive();
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("10250.00");
    }

    @Test
    void vaultWithdraw_burnsToSystemAccount() {
        UUID player = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        long txnId = ledgerService.vaultWithdraw("MyPlugin", player, new BigDecimal("1500.00"),
                "withdraw memo", initiator, null, null);

        assertThat(txnId).isPositive();
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("8500.00");
    }

    @Test
    void vaultDeposit_dedupKey_preventsDoublePost() {
        UUID player = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        long first  = ledgerService.vaultDeposit("MyPlugin", player, new BigDecimal("50.00"),
                "first", initiator, null, "dedup-key-A");
        long second = ledgerService.vaultDeposit("MyPlugin", player, new BigDecimal("50.00"),
                "replay", initiator, null, "dedup-key-A");

        assertThat(second).isEqualTo(first);
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("10050.00");
    }

    // ---------- adminReset ----------

    @Test
    void adminReset_returnsBalanceToConfiguredStarting() {
        UUID player = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        ledgerService.adminGive(player, new BigDecimal("5000.00"), "boost", admin);

        ledgerService.adminReset(player, admin);

        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("10000.00");
    }

    // ---------- transaction history ----------

    @Test
    void getTransactionHistory_returnsMostRecentFirst() {
        UUID player = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        Account p = ledgerService.resolveOrCreatePersonal(player);
        ledgerService.adminGive(player, new BigDecimal("100.00"), "first", admin);
        ledgerService.adminGive(player, new BigDecimal("200.00"), "second", admin);

        var page = ledgerService.getTransactionHistory(p.getAccountId(), 0, 10);

        assertThat(page.items()).hasSizeGreaterThanOrEqualTo(3); // initial seed + 2 admin gives
        assertThat(page.totalCount()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void getTransaction_andPostings_areReadable() {
        UUID player = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        long txnId = ledgerService.adminGive(player, new BigDecimal("50.00"), "for fetch", admin);

        var txn = ledgerService.getTransaction(txnId);
        assertThat(txn).isNotNull();
        assertThat(txn.getMessage()).isEqualTo("for fetch");

        var postings = ledgerService.getPostingsForTransaction(txnId);
        assertThat(postings).hasSize(2);
    }

    // ---------- authorizer-required edge ----------

    @Test
    void resolveOrCreatePersonal_existingAccountWithMissingBalanceRow_reSeedsAndFunds() throws Exception {
        UUID player = UUID.randomUUID();
        // Insert PERSONAL account without seeding balance (simulates a partial install).
        Account a = new Account();
        a.setAccountType(AccountType.PERSONAL);
        a.setOwnerUuid(player);
        a.setDisplayName(player.toString());
        a.setRequiresAuthorization(false);
        a.setArchived(false);
        a.setAllowOverdraft(false);
        a.setCreditLimit(BigDecimal.ZERO);
        accountMapper.insertAccount(a);
        // Note: NOT calling seedBalance — the ledger should detect this and re-seed.

        ledgerService.resolveOrCreatePersonal(player);

        // After the call, the player should have been seeded + funded with the starting balance.
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("10000.00");
    }

    @Test
    void getOrCreateGovernmentAccount_missingBalanceRow_reSeeds() throws Exception {
        // starting-balances was created in @BeforeEach via bootstrap. Drop its balance row
        // then trigger getOrCreateGovernmentAccount via bootstrap (which is idempotent).
        Account starting = accountService.getGovernmentAccountByName("starting-balances");
        try (var conn = dataSource.getConnection();
             var st = conn.prepareStatement("DELETE FROM account_balances_mat WHERE account_id = ?")) {
            st.setInt(1, starting.getAccountId());
            st.executeUpdate();
        }

        // Re-bootstrap: hits the "existing account, missing balance" branch.
        ledgerService.bootstrapGovernmentAccounts();

        // After re-seed, balance row exists at zero.
        BigDecimal bal = accountService.getBalanceReadOnly(starting.getAccountId());
        assertThat(bal).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void transfer_authorizerRequiredOnDestination_andNotPermitted_throws() {
        Account src = createPersonalAccount(1000);
        Account dest = createAuthorizationRequiredAccount(0);
        UUID rogue = UUID.randomUUID();

        assertThatThrownBy(() -> ledgerService.transfer(new TransferRequest(
                src.getAccountId(), dest.getAccountId(), new BigDecimal("10.00"),
                "rogue inbound", TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, rogue,
                "test", null)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not permitted");
    }

    // ---------- concurrency: deadlock-safe lock ordering ----------

    @Test
    void concurrentSwappedTransfers_neverDeadlock_andConserveMoney() throws Exception {
        // Transfers lock both balance rows FOR UPDATE in ascending account-id order,
        // so A→B and B→A can't form a lock cycle. Hammer both directions at once and
        // assert zero transient failures + money conserved (ADT-50).
        Account a = createPersonalAccount(10_000);
        Account b = createPersonalAccount(10_000);

        int perDirection = 40;
        java.util.List<java.util.concurrent.Callable<Void>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < perDirection; i++) {
            tasks.add(() -> { transferOne(a, b); return null; });
            tasks.add(() -> { transferOne(b, a); return null; });
        }

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        java.util.List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        try {
            for (java.util.concurrent.Future<Void> f : pool.invokeAll(tasks)) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    errors.add(e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(errors).as("no transient deadlock/failure under swapped-direction load").isEmpty();
        // Equal traffic each way nets to zero; money is conserved regardless.
        assertThat(balanceOf(a)).isEqualByComparingTo("10000.00");
        assertThat(balanceOf(b)).isEqualByComparingTo("10000.00");
        assertThat(balanceOf(a).add(balanceOf(b))).isEqualByComparingTo("20000.00");
    }

    private void transferOne(Account from, Account to) {
        ledgerService.transfer(new TransferRequest(
                from.getAccountId(), to.getAccountId(),
                new BigDecimal("1.00"), "concurrency test",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null,
                TreasuryConstants.TREASURY_PLUGIN_NAME, null));
    }

    // ---------- sweepAll (firm-disband drain) ----------

    @Test
    void sweepAll_movesTheEntireLiveBalance_conservingValue() {
        Account firmAcc = createPersonalAccount(0);
        Account payout  = createPersonalAccount(0);
        Account funder  = createPersonalAccount(1_000);
        // Fund the firm account with a plain ledger transfer (NOT adminGive — adminGive
        // resolves the owner via resolveOrCreatePersonal, which also seeds the 10_000
        // starting balance and would mask the swept amount). This keeps the firm balance
        // an exact, trigger-maintained 737.50.
        ledgerService.transfer(new TransferRequest(
                funder.getAccountId(), firmAcc.getAccountId(),
                new BigDecimal("737.50"), "seed",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null,
                TreasuryConstants.TREASURY_PLUGIN_NAME, null));
        BigDecimal before = balanceOf(firmAcc).add(balanceOf(payout));

        java.util.OptionalLong txn = ledgerService.sweepAll(
                firmAcc.getAccountId(), payout.getAccountId(), "Firm disbanded", UUID.randomUUID(), "BusinessPlugin");

        assertThat(txn).isPresent();
        // Exact conservation: everything left the source, nothing was created or lost.
        assertThat(balanceOf(firmAcc)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(payout)).isEqualByComparingTo("737.50");
        assertThat(balanceOf(firmAcc).add(balanceOf(payout))).isEqualByComparingTo(before);
    }

    @Test
    void sweepAll_zeroBalance_isNoOp() {
        Account firmAcc = createPersonalAccount(0);
        Account payout  = createPersonalAccount(0);

        java.util.OptionalLong txn = ledgerService.sweepAll(
                firmAcc.getAccountId(), payout.getAccountId(), "Firm disbanded", UUID.randomUUID(), "BusinessPlugin");

        assertThat(txn).isEmpty();
        assertThat(balanceOf(payout)).isEqualByComparingTo("0.00");
    }

    @Test
    void sweepAll_concurrentCreditLandingBeforeSweep_isNotStranded() throws Exception {
        // Model the finding: a credit lands, THEN the disband sweep runs. Because the
        // amount is read under the sweep's FOR UPDATE lock (not a pre-credit snapshot),
        // the whole post-credit balance is swept and nothing is stranded.
        Account firmAcc = createPersonalAccount(0);
        Account payout  = createPersonalAccount(0);
        Account funder  = createPersonalAccount(1_000);

        ledgerService.transfer(new TransferRequest(
                funder.getAccountId(), firmAcc.getAccountId(),
                new BigDecimal("400.00"), "late credit",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null,
                TreasuryConstants.TREASURY_PLUGIN_NAME, null));

        java.util.OptionalLong txn = ledgerService.sweepAll(
                firmAcc.getAccountId(), payout.getAccountId(), "Firm disbanded", UUID.randomUUID(), "BusinessPlugin");

        assertThat(txn).isPresent();
        assertThat(balanceOf(firmAcc)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(payout)).isEqualByComparingTo("400.00");
    }

    // ---------- helpers ----------

    private BigDecimal balanceOf(Account a) {
        AccountBalance b = accountMapper.readBalance(a.getAccountId());
        return b == null ? BigDecimal.ZERO : b.getBalance();
    }

    private Account createPersonalAccount(double startingBalance) {
        UUID owner = UUID.randomUUID();
        Account a = new Account();
        a.setAccountType(AccountType.PERSONAL);
        a.setOwnerUuid(owner);
        a.setDisplayName(owner.toString());
        a.setRequiresAuthorization(false);
        a.setArchived(false);
        a.setAllowOverdraft(false);
        a.setCreditLimit(BigDecimal.ZERO);
        accountMapper.insertAccount(a);
        accountMapper.seedBalance(a.getAccountId());
        if (startingBalance > 0) {
            seedBalance(a, BigDecimal.valueOf(startingBalance).setScale(2, java.math.RoundingMode.HALF_EVEN));
        }
        return a;
    }

    private Account createOverdraftAccount(double startingBalance, BigDecimal creditLimit) {
        UUID owner = UUID.randomUUID();
        Account a = new Account();
        a.setAccountType(AccountType.PERSONAL);
        a.setOwnerUuid(owner);
        a.setDisplayName(owner.toString());
        a.setRequiresAuthorization(false);
        a.setArchived(false);
        a.setAllowOverdraft(true);
        a.setCreditLimit(creditLimit);
        accountMapper.insertAccount(a);
        accountMapper.seedBalance(a.getAccountId());
        if (startingBalance > 0) {
            seedBalance(a, BigDecimal.valueOf(startingBalance).setScale(2, java.math.RoundingMode.HALF_EVEN));
        }
        return a;
    }

    private Account createAuthorizationRequiredAccount(double startingBalance) {
        UUID owner = UUID.randomUUID();
        Account a = new Account();
        a.setAccountType(AccountType.BUSINESS);
        a.setOwnerUuid(owner);
        a.setDisplayName("biz-" + owner);
        // Insert with requires_authorization=false so the seed transfer can complete,
        // then flip the flag once the account is funded.
        a.setRequiresAuthorization(false);
        a.setArchived(false);
        a.setAllowOverdraft(false);
        a.setCreditLimit(BigDecimal.ZERO);
        accountMapper.insertAccount(a);
        accountMapper.seedBalance(a.getAccountId());
        if (startingBalance > 0) {
            seedBalance(a, BigDecimal.valueOf(startingBalance).setScale(2, java.math.RoundingMode.HALF_EVEN));
        }
        a.setRequiresAuthorization(true);
        accountMapper.updateAccount(a);
        return a;
    }

    /**
     * Funds a freshly created test account by transferring from the starting-balances
     * GOVERNMENT account (which has overdraft enabled and an unlimited credit line).
     */
    private void seedBalance(Account target, BigDecimal amount) {
        Account gov = accountService.getGovernmentAccountByName("starting-balances");
        ledgerService.transfer(new TransferRequest(
                gov.getAccountId(), target.getAccountId(), amount, "test seed",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null,
                TreasuryConstants.TREASURY_PLUGIN_NAME, null));
    }
}
