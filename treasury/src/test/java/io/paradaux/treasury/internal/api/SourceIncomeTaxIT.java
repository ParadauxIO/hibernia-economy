package io.paradaux.treasury.internal.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.util.Modules;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.guice.DatabaseModule;
import io.paradaux.treasury.internal.model.config.SourceIncomeTaxConfiguration;
import io.paradaux.treasury.model.tax.TaxResult;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import io.paradaux.treasury.testsupport.TestServicesModule;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link TaxApi#applySourceIncomeTax} with the source income tax feature
 * enabled. Uses {@code Modules.override} to swap in an enabled
 * {@link SourceIncomeTaxConfiguration} bound to the production tax-income account.
 */
class SourceIncomeTaxIT extends IntegrationTestBase {

    private TaxApi taxApi;
    private LedgerService ledgerService;
    private AccountService accountService;

    @BeforeEach
    void setUpEnabledIncomeTax() {
        // Parent @BeforeEach has already set up dataSource and a default injector.
        // Override the source-income-tax binding to enable it for this test.
        this.injector = Guice.createInjector(
                new DatabaseModule(this.dataSource),
                Modules.override(new TestServicesModule()).with(new EnabledSourceIncomeTaxModule()));

        ledgerService = injector.getInstance(LedgerService.class);
        accountService = injector.getInstance(AccountService.class);
        taxApi = injector.getInstance(TaxApi.class);
        ledgerService.bootstrapGovernmentAccounts();
    }

    @Test
    void applySourceIncomeTax_pluginRateZero_returnsSkipped() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        // Plugin "Free" has rate 0 → skipped
        TaxResult result = taxApi.applySourceIncomeTax(player, new BigDecimal("100.00"), "Free");

        assertThat(result).isInstanceOf(TaxResult.Skipped.class);
        assertThat(((TaxResult.Skipped) result).reason()).contains("rate");
    }

    @Test
    void applySourceIncomeTax_unknownPlayer_returnsSkipped() {
        UUID nobody = UUID.randomUUID();

        TaxResult result = taxApi.applySourceIncomeTax(nobody, new BigDecimal("100.00"), "Realty");

        assertThat(result).isInstanceOf(TaxResult.Skipped.class);
        assertThat(((TaxResult.Skipped) result).reason()).contains("No personal account");
    }

    @Test
    void applySourceIncomeTax_subMinimumComputedTax_returnsSkipped() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        // 0.10 * 0.10 = 0.01 — exactly at minimum, would collect.
        // 0.01 * 0.10 = 0.001 → rounds to 0 → skipped.
        TaxResult result = taxApi.applySourceIncomeTax(player, new BigDecimal("0.01"), "Realty");

        assertThat(result).isInstanceOf(TaxResult.Skipped.class);
    }

    @Test
    void applySourceIncomeTax_validDeposit_collects() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        // Realty rate = 0.10, deposit 200 → tax 20.00
        TaxResult result = taxApi.applySourceIncomeTax(player, new BigDecimal("200.00"), "Realty");

        assertThat(result).isInstanceOf(TaxResult.Collected.class);
        assertThat(((TaxResult.Collected) result).amountCharged()).isEqualByComparingTo("20.00");
        // Player started at 10 000, lost 20 → 9 980
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("9980.00");
    }

    @Test
    void destinationFallback_whenConfiguredAccountIsMissing() {
        // Override again to point at a non-existent destination.
        this.injector = Guice.createInjector(
                new DatabaseModule(this.dataSource),
                Modules.override(new TestServicesModule()).with(new BrokenDestinationModule()));
        TaxApi api = injector.getInstance(TaxApi.class);
        AccountService accs = injector.getInstance(AccountService.class);
        LedgerService ls = injector.getInstance(LedgerService.class);
        ls.bootstrapGovernmentAccounts();

        UUID player = UUID.randomUUID();
        ls.resolveOrCreatePersonal(player);

        TaxResult result = api.applySourceIncomeTax(player, new BigDecimal("100.00"), "Realty");

        // Falls back to default tax account but still collects.
        assertThat(result).isInstanceOf(TaxResult.Collected.class);
        // Default tax account credited with 10.00.
        assertThat(accs.getBalanceReadOnly(api.getDefaultTaxAccountId())).isEqualByComparingTo("10.00");
    }

    /** Provides an enabled SourceIncomeTaxConfiguration with one explicit plugin rate. */
    static class EnabledSourceIncomeTaxModule extends AbstractModule {
        @Provides
        @Singleton
        SourceIncomeTaxConfiguration enabled() {
            return SourceIncomeTaxConfiguration.forTesting(
                    true,
                    new BigDecimal("0.05"),
                    "DCGovernment",
                    Map.of("Realty", new BigDecimal("0.10"), "Free", BigDecimal.ZERO));
        }
    }

    /** Provides an enabled config that points at a non-existent government account. */
    static class BrokenDestinationModule extends AbstractModule {
        @Provides
        @Singleton
        SourceIncomeTaxConfiguration broken() {
            return SourceIncomeTaxConfiguration.forTesting(
                    true, new BigDecimal("0.05"), "DoesNotExist", Map.of("Realty", new BigDecimal("0.10")));
        }
    }
}
