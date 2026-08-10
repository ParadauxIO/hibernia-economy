package io.paradaux.treasury.services.impl;

import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.mappers.LedgerMapper;
import io.paradaux.treasury.model.Page;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.LedgerTxn;
import io.paradaux.treasury.model.economy.TransactionEntry;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import io.paradaux.treasury.utils.TreasuryConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Broader behavioural tests: archival semantics, pagination correctness,
 * plugin attribution, and a basic concurrency smoke test. Complements the
 * targeted regressions in {@code LedgerServiceTransferIT} and {@code GovServiceIT}.
 */
class LedgerServiceBreadthIT extends IntegrationTestBase {

    private LedgerService ledgerService;
    private AccountService accountService;
    private AccountMapper accountMapper;
    private LedgerMapper ledgerMapper;

    @BeforeEach
    void setUp() {
        ledgerService = injector.getInstance(LedgerService.class);
        accountService = injector.getInstance(AccountService.class);
        accountMapper = injector.getInstance(AccountMapper.class);
        ledgerMapper = injector.getInstance(LedgerMapper.class);
        ledgerService.bootstrapGovernmentAccounts();
    }

    // ---------- Archived-account semantics ----------

    @Test
    void transfer_fromArchivedAccount_stillSucceedsAtServiceLayer() {
        // The service layer doesn't enforce "no transfers from archived accounts" —
        // archival is enforced higher up (commands, business logic). This test pins
        // the current contract so a future change forces an explicit decision.
        UUID owner = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(owner);
        Account p = accountService.getAccountByUUID(owner);
        accountService.archiveAccount(p.getAccountId());

        Account dest = createPersonalAccount(0);
        long txnId = ledgerService.transfer(new TransferRequest(
                p.getAccountId(), dest.getAccountId(), new BigDecimal("100.00"),
                "from archived", TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null,
                "test", null));

        assertThat(txnId).isPositive();
    }

    @Test
    void archiveAccount_thenUnarchive_restoresMutability() {
        UUID owner = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(owner);
        int id = accountService.getPersonalAccountId(owner);

        accountService.archiveAccount(id);
        assertThat(accountService.getAccountById(id).isArchived()).isTrue();

        accountService.unarchiveAccount(id);
        assertThat(accountService.getAccountById(id).isArchived()).isFalse();
    }

    // ---------- Transaction history pagination ----------

    @Test
    void getTransactionHistory_paginatesCorrectlyAcrossManyTxns() {
        UUID player = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        Account p = accountService.getAccountByUUID(player);

        // 25 admin gives → 25 txns hitting the player account (plus the seed = 26).
        for (int i = 1; i <= 25; i++) {
            ledgerService.adminGive(player, new BigDecimal(i + ".00"), "g" + i, admin);
        }

        Page<TransactionEntry> page1 = ledgerService.getTransactionHistory(p.getAccountId(), 0, 10);
        Page<TransactionEntry> page2 = ledgerService.getTransactionHistory(p.getAccountId(), 10, 10);
        Page<TransactionEntry> page3 = ledgerService.getTransactionHistory(p.getAccountId(), 20, 10);
        Page<TransactionEntry> page4 = ledgerService.getTransactionHistory(p.getAccountId(), 30, 10);

        assertThat(page1.items()).hasSize(10);
        assertThat(page2.items()).hasSize(10);
        assertThat(page3.items()).hasSizeGreaterThanOrEqualTo(6);
        assertThat(page4.items()).isEmpty();
        assertThat(page1.totalCount()).isEqualTo(page2.totalCount()).isEqualTo(page3.totalCount());
        assertThat(page1.hasMore()).isTrue();
        assertThat(page2.hasMore()).isTrue();
    }

    @Test
    void getTransactionHistory_mergesAcrossAccountsMostRecentFirst() {
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(p1);
        ledgerService.resolveOrCreatePersonal(p2);
        int a1 = accountService.getPersonalAccountId(p1);
        int a2 = accountService.getPersonalAccountId(p2);

        for (int i = 0; i < 6; i++) ledgerService.adminGive(p1, new BigDecimal("1.00"), "a" + i, admin);
        for (int i = 0; i < 4; i++) ledgerService.adminGive(p2, new BigDecimal("1.00"), "b" + i, admin);

        Page<TransactionEntry> merged = ledgerService.getTransactionHistory(List.of(a1, a2), 0, 100);

        // Every posting belongs to one of the two accounts, the total spans both,
        // and results are merged most-recent-first (posting_id strictly descending).
        assertThat(merged.items()).allMatch(t -> t.getAccountId() == a1 || t.getAccountId() == a2);
        assertThat(merged.totalCount()).isEqualTo(merged.items().size());
        assertThat(merged.totalCount()).isGreaterThanOrEqualTo(10);
        List<TransactionEntry> items = merged.items();
        for (int i = 1; i < items.size(); i++) {
            assertThat(items.get(i).getPostingId()).isLessThan(items.get(i - 1).getPostingId());
        }
    }

    @Test
    void getTransactionHistory_emptyAccountList_returnsEmptyPage() {
        Page<TransactionEntry> page = ledgerService.getTransactionHistory(List.of(), 0, 10);
        assertThat(page.items()).isEmpty();
        assertThat(page.totalCount()).isZero();
    }

    @Test
    void getTransactionHistory_returnsMostRecentFirst() {
        UUID player = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        Account p = accountService.getAccountByUUID(player);

        long firstTxn = ledgerService.adminGive(player, new BigDecimal("1.00"), "first", admin);
        long secondTxn = ledgerService.adminGive(player, new BigDecimal("2.00"), "second", admin);

        Page<TransactionEntry> page = ledgerService.getTransactionHistory(p.getAccountId(), 0, 5);

        // First entry is most recent (descending by settlement time).
        assertThat(page.items().get(0).getTxnId()).isEqualTo(secondTxn);
        // First admin give comes after the seed.
        long secondMostRecentTxn = page.items().get(1).getTxnId();
        assertThat(secondMostRecentTxn).isEqualTo(firstTxn);
    }

    @Test
    void countTransactionsByAccount_matchesTotalCount() {
        UUID player = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        Account p = accountService.getAccountByUUID(player);

        for (int i = 0; i < 7; i++) {
            ledgerService.adminGive(player, BigDecimal.ONE, "g", admin);
        }

        Page<TransactionEntry> page = ledgerService.getTransactionHistory(p.getAccountId(), 0, 3);
        assertThat(page.totalCount()).isEqualTo(8); // seed + 7
    }

    // ---------- Plugin attribution ----------

    @Test
    void transfer_preservesPluginSystemAttribution() {
        Account a = createPersonalAccount(500);
        Account b = createPersonalAccount(0);

        long txnId = ledgerService.transfer(new TransferRequest(
                a.getAccountId(), b.getAccountId(), new BigDecimal("10.00"),
                "tagged", UUID.randomUUID(), null,
                "Realty", null));

        LedgerTxn txn = ledgerMapper.findTxnById(txnId);
        assertThat(txn.getPluginSystem()).isEqualTo("Realty");
    }

    @Test
    void vaultDeposit_recordsPluginNameAsAttribution() {
        UUID player = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        long txnId = ledgerService.vaultDeposit("ShopPlugin", player, new BigDecimal("5.00"),
                "shop sale", initiator, null, null);

        LedgerTxn txn = ledgerMapper.findTxnById(txnId);
        assertThat(txn.getPluginSystem()).isEqualTo("ShopPlugin");
    }

    // ---------- Concurrency smoke test ----------

    @Test
    void transfer_concurrentDebitsAgainstSameSource_neverOverdrawWithoutOverdraft() throws Exception {
        // Source has 1 000 with no overdraft. Fire 20 concurrent transfers of 100
        // each. The SAFETY property is "no overdraft, money is conserved" —
        // *not* an exact success count. Under sufficient lock contention some
        // transactions may surface transient errors (deadlock, row-version);
        // the suite should still validate that no money is lost, no balance
        // ever drops below 0, and the source-debit total exactly equals the
        // sink-credit total.
        Account source = createPersonalAccount(1_000);
        Account sink = createPersonalAccount(0);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger expected  = new AtomicInteger(); // insufficient-funds rejections
        AtomicInteger transient_ = new AtomicInteger(); // anything else (lock conflicts etc.)
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    ledgerService.transfer(new TransferRequest(
                            source.getAccountId(), sink.getAccountId(), new BigDecimal("100.00"),
                            "race", TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null,
                            "test", null));
                    successes.incrementAndGet();
                } catch (IllegalStateException e) {
                    if (String.valueOf(e.getMessage()).contains("Insufficient funds")) {
                        expected.incrementAndGet();
                    } else {
                        transient_.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    // mybatis wraps SQL errors in PersistenceException — count as transient
                    transient_.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(successes.get() + expected.get() + transient_.get()).isEqualTo(threads);
        // At least one must succeed for the test to be meaningful.
        assertThat(successes.get()).isGreaterThan(0);
        // SAFETY: never overdraw the source.
        assertThat(successes.get()).isLessThanOrEqualTo(10);

        BigDecimal finalSource = accountService.getBalanceReadOnly(source.getAccountId());
        BigDecimal finalSink   = accountService.getBalanceReadOnly(sink.getAccountId());

        // SAFETY: no negative balance.
        assertThat(finalSource).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        // CONSERVATION: every cent debited from source landed in sink.
        BigDecimal expectedMoved = new BigDecimal(successes.get() * 100).setScale(2, java.math.RoundingMode.HALF_EVEN);
        assertThat(finalSink).isEqualByComparingTo(expectedMoved);
        BigDecimal sourceDebited = new BigDecimal("1000.00").subtract(finalSource);
        assertThat(sourceDebited).isEqualByComparingTo(expectedMoved);
    }

    // ---------- Helpers ----------

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
            Account gov = accountService.getGovernmentAccountByName("starting-balances");
            ledgerService.transfer(new TransferRequest(
                    gov.getAccountId(), a.getAccountId(),
                    BigDecimal.valueOf(startingBalance).setScale(2, java.math.RoundingMode.HALF_EVEN),
                    "test seed",
                    TreasuryConstants.VIRTUAL_TREASURY_INITIATOR, null,
                    TreasuryConstants.TREASURY_PLUGIN_NAME, null));
        }
        return a;
    }
}
