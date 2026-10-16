package io.paradaux.treasury.api.impl;

import com.google.inject.Guice;
import com.google.inject.Injector;
import io.paradaux.hibernia.testsupport.contract.TreasuryApiContract;
import io.paradaux.hibernia.testsupport.db.TestDatabaseExtension;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.guice.DatabaseModule;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import io.paradaux.treasury.testsupport.TestServicesModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Runs the shared {@link TreasuryApiContract} against the real {@link TreasuryApiImpl}
 * — production services, MyBatis mappers, triggers — on the treasury test database,
 * with the harness's per-test reset and ledger invariant check. This is the
 * behaviour business's Mockito stubs of {@code TreasuryApi} are assumed to mirror.
 */
@Tag("integration")
class TreasuryApiImplContractIT extends TreasuryApiContract {

    @RegisterExtension
    static final TestDatabaseExtension DB = new TestDatabaseExtension(IntegrationTestBase.DATABASE);

    private TreasuryApi api;
    private int faucet;

    @BeforeEach
    void wire() {
        Injector injector = Guice.createInjector(new DatabaseModule(DB.dataSource()), new TestServicesModule());
        injector.getInstance(LedgerService.class).bootstrapGovernmentAccounts();
        api = injector.getInstance(TreasuryApiImpl.class);
        faucet = injector.getInstance(AccountService.class)
                .getGovernmentAccountByName("starting-balances")
                .getAccountId();
    }

    @Override
    protected TreasuryApi api() {
        return api;
    }

    @Override
    protected int faucetAccountId() {
        return faucet;
    }
}
