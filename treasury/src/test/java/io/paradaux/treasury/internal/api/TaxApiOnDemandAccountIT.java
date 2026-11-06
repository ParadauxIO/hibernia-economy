package io.paradaux.treasury.internal.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.util.Modules;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.guice.DatabaseModule;
import io.paradaux.treasury.internal.model.config.GovernmentConfiguration;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import io.paradaux.treasury.testsupport.TestConfigs;
import io.paradaux.treasury.testsupport.TestServicesModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@code TaxApiImpl.resolveOrCreateTaxAccount}: the on-demand creation
 * fallback that fires when the configured default-tax-account doesn't exist in the DB.
 */
class TaxApiOnDemandAccountIT extends IntegrationTestBase {

    private TaxApi taxApi;
    private AccountService accountService;

    @BeforeEach
    void wireMisalignedConfig() {
        // GovernmentConfiguration says tax-income is "Mismatched" — but bootstrap is
        // never run, so no GOVERNMENT account by that name exists. The first call to
        // getDefaultTaxAccountId triggers on-demand creation.
        this.injector = Guice.createInjector(
                new DatabaseModule(this.dataSource),
                Modules.override(new TestServicesModule()).with(new MisalignedGovModule()));
        taxApi = injector.getInstance(TaxApi.class);
        accountService = injector.getInstance(AccountService.class);
        // NOTE: not calling bootstrapGovernmentAccounts so the on-demand path fires.
    }

    @Test
    void getDefaultTaxAccountId_createsAccountOnDemand() {
        int id = taxApi.getDefaultTaxAccountId();

        // Account was created by the on-demand fallback.
        var created = accountService.getAccountById(id);
        assertThat(created).isNotNull();
        assertThat(created.getDisplayName()).isEqualTo("Mismatched");
        assertThat(created.getCreditLimit()).isEqualByComparingTo("-1");
    }

    static class MisalignedGovModule extends AbstractModule {
        @Provides
        @Singleton
        GovernmentConfiguration cfg() {
            return TestConfigs.government("starting-balances", "Mismatched", "GovernmentFines");
        }
    }
}
