package io.paradaux.treasury.services.impl;

import static org.assertj.core.api.Assertions.assertThat;

import io.paradaux.treasury.mappers.AccountMapper;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;

/** ADT-50: exercise the async first-join service with real competing MariaDB transactions. */
class ConcurrentFirstLoginIT extends IntegrationTestBase {

    @Test
    void concurrentFirstLogin_returnsOneAccountAndFundsItExactlyOnce() throws Exception {
        LedgerService ledger = injector.getInstance(LedgerService.class);
        AccountService accounts = injector.getInstance(AccountService.class);
        ledger.bootstrapGovernmentAccounts();
        UUID player = UUID.randomUUID();
        assertThat(accounts.getAccountByUUID(player)).isNull();

        // Hold both calls after their missing-account reads, before either INSERT.
        // This preserves real SQL, uniqueness constraints and transaction handling.
        var race = new PersonalInsertBarrier(player);
        injector.getInstance(SqlSessionFactory.class).getConfiguration().addInterceptor(race);
        var workers = Executors.newFixedThreadPool(2);
        Account first;
        Account second;
        try {
            var firstLogin = workers.submit(() -> ledger.resolveOrCreatePersonal(player));
            var secondLogin = workers.submit(() -> ledger.resolveOrCreatePersonal(player));
            first = firstLogin.get(30, TimeUnit.SECONDS);
            second = secondLogin.get(30, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.getAccountId()).isEqualTo(first.getAccountId());
        assertThat(first.getAccountType()).isEqualTo(AccountType.PERSONAL);
        assertThat(first.getOwnerUuid()).isEqualTo(player);
        assertThat(accounts.getAccountsByTypeAndOwner(AccountType.PERSONAL, player))
                .extracting(Account::getAccountId)
                .containsExactly(first.getAccountId());
        assertThat(accounts.getBalanceReadOnly(first.getAccountId())).isEqualByComparingTo("10000.00");
        Account government = accounts.getGovernmentAccountByName("starting-balances");
        assertThat(accounts.getBalanceReadOnly(government.getAccountId())).isEqualByComparingTo("-10000.00");

        // A subsequent join must remain harmless after the concurrent calls commit.
        assertThat(ledger.resolveOrCreatePersonal(player).getAccountId()).isEqualTo(first.getAccountId());
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM ledger_txns")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*), SUM(amount) FROM ledger_postings")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
                assertThat(rows.getBigDecimal(2)).isEqualByComparingTo("0.00");
            }
        }
        // IntegrationTestBase also checks the persisted ledger/balance invariants.
    }

    @Intercepts(
            @Signature(
                    type = Executor.class,
                    method = "update",
                    args = {MappedStatement.class, Object.class}))
    static final class PersonalInsertBarrier implements Interceptor {
        private final UUID player;
        private final CountDownLatch inserting = new CountDownLatch(2);

        PersonalInsertBarrier(UUID player) {
            this.player = player;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
            if (statement.getId().equals(AccountMapper.class.getName() + ".insertAccount")
                    && invocation.getArgs()[1] instanceof Account account
                    && account.getAccountType() == AccountType.PERSONAL
                    && player.equals(account.getOwnerUuid())) {
                inserting.countDown();
                if (!inserting.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Both first-login transactions must reach the personal-account INSERT");
                }
            }
            return invocation.proceed();
        }
    }
}
