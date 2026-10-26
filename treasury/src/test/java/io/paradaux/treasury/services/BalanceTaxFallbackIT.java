package io.paradaux.treasury.services;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.util.Modules;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.guice.DatabaseModule;
import io.paradaux.treasury.model.config.BalanceTaxConfiguration;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import io.paradaux.treasury.testsupport.TestServicesModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@code BalanceTaxService} falls back to the default tax-collection account
 * when the configured government-account doesn't exist in the database.
 */
class BalanceTaxFallbackIT extends IntegrationTestBase {

    private static final long ONE_WEEK_SECS = 7L * 24 * 3600;

    private LedgerService ledgerService;
    private AccountService accountService;
    private BalanceTaxService balanceTaxService;
    private PlayerDirectoryService directory;
    private TaxApi taxApi;

    @BeforeEach
    void wireBrokenDestination() {
        // Override the BalanceTaxConfiguration so it points at a non-existent account.
        this.injector = Guice.createInjector(
                new DatabaseModule(this.dataSource),
                Modules.override(new TestServicesModule()).with(new BrokenDestinationModule())
        );
        ledgerService     = injector.getInstance(LedgerService.class);
        accountService    = injector.getInstance(AccountService.class);
        balanceTaxService = injector.getInstance(BalanceTaxService.class);
        directory         = injector.getInstance(PlayerDirectoryService.class);
        taxApi            = injector.getInstance(TaxApi.class);
        ledgerService.bootstrapGovernmentAccounts();
    }

    /** Mirrors {@code PlayerLoginListener}: directory records the login, tax collects. */
    private void login(UUID player, long epochSeconds) {
        Long previous = directory.recordLogin(player, "P" + player.toString().substring(0, 8), epochSeconds);
        balanceTaxService.collect(player, previous, epochSeconds);
    }

    @Test
    void missingDestination_routesToDefaultTaxAccount() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        ledgerService.adminSet(player, new BigDecimal("100000.00"), "setup", UUID.randomUUID());

        long t0 = 1_700_000_000L;
        login(player, t0);
        login(player, t0 + ONE_WEEK_SECS / 2);

        // Tax collection should still succeed — fallback to default tax account.
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("99500.00");
        // Default tax account should hold the collected amount.
        int defaultTaxId = taxApi.getDefaultTaxAccountId();
        assertThat(accountService.getBalanceReadOnly(defaultTaxId)).isEqualByComparingTo("500.00");
    }

    static class BrokenDestinationModule extends AbstractModule {
        @Provides @Singleton
        BalanceTaxConfiguration brokenBalanceConfig() {
            NavigableMap<BigDecimal, BigDecimal> brackets = new TreeMap<>();
            brackets.put(new BigDecimal("0.00"),       BigDecimal.ZERO);
            brackets.put(new BigDecimal("100000.00"),  new BigDecimal("0.01"));
            return BalanceTaxConfiguration.forTesting(true, "DoesNotExist", brackets);
        }
    }
}
