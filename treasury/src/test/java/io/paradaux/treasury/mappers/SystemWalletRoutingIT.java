package io.paradaux.treasury.mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.economy.AccountType;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import io.paradaux.treasury.utils.TreasuryConstants;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SystemWalletRoutingIT extends IntegrationTestBase {
    @Test
    void renamedWalletRetainsOriginalPluginRoutingFundsOwnerAndUniqueKey() throws Exception {
        AccountService service = injector.getInstance(AccountService.class);
        AccountMapper mapper = injector.getInstance(AccountMapper.class);
        LedgerService ledger = injector.getInstance(LedgerService.class);
        UUID owner = UUID.randomUUID();
        int walletId = service.getOrCreateSystemAccountId("FixturePlugin", owner);
        Account destination = service.createAccount(AccountType.BUSINESS, UUID.randomUUID(), "Destination");
        ledger.adminTransfer(new TransferRequest(
                walletId,
                destination.getAccountId(),
                new BigDecimal("12.34"),
                "routing fixture",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                null,
                TreasuryConstants.TREASURY_PLUGIN_NAME,
                null));
        Account wallet = mapper.findById(walletId);
        wallet.setDisplayName("Renamed wallet");
        mapper.updateAccount(wallet);

        // A fresh service lookup and the race-recovery locking read must both find
        // the ORIGINAL wallet, not create a new faucet or depend on a warm cache.
        assertThat(service.getOrCreateSystemAccountId("FixturePlugin", UUID.randomUUID()))
                .isEqualTo(walletId);
        assertThat(mapper.findSystemAccountIdForPluginLocking("fixtureplugin")).isEqualTo(walletId);
        assertThat(mapper.findSystemAccountForPlugin("Renamed wallet")).isNull();
        assertThat(mapper.findById(walletId).getOwnerUuid()).isEqualTo(owner);
        assertThat(mapper.findById(walletId).getDisplayName()).isEqualTo("Renamed wallet");
        assertThat(mapper.readBalance(walletId).getBalance()).isEqualByComparingTo("-12.34");
        try (var c = dataSource.getConnection();
                var statement = c.createStatement()) {
            // Direct/legacy insert with a different display label still cannot
            // claim the same internal plugin identity (case-insensitive as V24).
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO accounts(account_type,owner_uuid_bin,"
                            + "display_name,system_plugin_key) VALUES ('SYSTEM',UNHEX(REPEAT('11',16)),"
                            + "'Another label','FIXTUREPLUGIN')"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                            "UPDATE accounts SET system_plugin_key='OtherPlugin' " + "WHERE account_id=" + walletId))
                    .isInstanceOf(SQLException.class);
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM accounts WHERE account_type='SYSTEM'")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void genericAndLegacyInsertsCaptureKeysAndRejectInvalidIdentityWrites() throws Exception {
        AccountService service = injector.getInstance(AccountService.class);
        AccountMapper mapper = injector.getInstance(AccountMapper.class);
        Account generic = service.createAccount(AccountType.SYSTEM, UUID.randomUUID(), "GenericPlugin");
        generic.setDisplayName("Generic label");
        mapper.updateAccount(generic);
        assertThat(service.getOrCreateSystemAccountId("GenericPlugin", UUID.randomUUID()))
                .isEqualTo(generic.getAccountId());
        try (var c = dataSource.getConnection();
                var statement = c.createStatement()) {
            statement.executeUpdate("INSERT INTO accounts(account_type,owner_uuid_bin,display_name) "
                    + "VALUES ('SYSTEM',UNHEX(REPEAT('11',16)),'LegacyPlugin')");
            statement.executeUpdate(
                    "INSERT INTO account_balances_mat(account_id,balance) SELECT account_id,0 FROM accounts WHERE system_plugin_key='LegacyPlugin'");
            statement.executeUpdate(
                    "UPDATE accounts SET display_name='Legacy label' WHERE system_plugin_key='LegacyPlugin'");
            assertThat(mapper.findSystemAccountForPlugin("LegacyPlugin").getDisplayName())
                    .isEqualTo("Legacy label");
            assertThatThrownBy(() -> statement.executeUpdate(
                            "INSERT INTO accounts(account_type,owner_uuid_bin,display_name,system_plugin_key) "
                                    + "VALUES ('BUSINESS',UNHEX(REPEAT('11',16)),'Business label','Forbidden')"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                            "INSERT INTO accounts(account_type,owner_uuid_bin,display_name,system_plugin_key) "
                                    + "VALUES ('SYSTEM',UNHEX(REPEAT('11',16)),'Blank key',' ')"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                            "UPDATE accounts SET system_plugin_key=NULL WHERE system_plugin_key='LegacyPlugin'"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate(
                            "UPDATE accounts SET account_type='BUSINESS' WHERE system_plugin_key='LegacyPlugin'"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void concurrentColdResolversKeepOneWalletAndOneBalanceAfterRename() throws Exception {
        AccountService service = injector.getInstance(AccountService.class);
        AccountMapper mapper = injector.getInstance(AccountMapper.class);
        UUID owner = UUID.randomUUID();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++)
                results.add(pool.submit(() -> {
                    gate.await();
                    return service.getOrCreateSystemAccountId("RacingPlugin", owner);
                }));
            gate.countDown();
            int id = results.getFirst().get(30, java.util.concurrent.TimeUnit.SECONDS);
            for (var result : results)
                assertThat(result.get(30, java.util.concurrent.TimeUnit.SECONDS))
                        .isEqualTo(id);
            Account account = mapper.findById(id);
            account.setDisplayName("Racing label");
            mapper.updateAccount(account);
            results.clear();
            for (int i = 0; i < 8; i++)
                results.add(pool.submit(() -> service.getOrCreateSystemAccountId("RacingPlugin", owner)));
            for (var result : results)
                assertThat(result.get(30, java.util.concurrent.TimeUnit.SECONDS))
                        .isEqualTo(id);
            assertThat(mapper.findSystemAccountIdForPluginLocking("RacingPlugin"))
                    .isEqualTo(id);
            try (var c = dataSource.getConnection();
                    var statement = c.createStatement();
                    var rows = statement.executeQuery(
                            "SELECT COUNT(*),COUNT(b.account_id) FROM accounts a LEFT JOIN account_balances_mat b USING(account_id) WHERE account_type='SYSTEM'")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
                assertThat(rows.getInt(2)).isEqualTo(1);
            }
        }
    }
}
