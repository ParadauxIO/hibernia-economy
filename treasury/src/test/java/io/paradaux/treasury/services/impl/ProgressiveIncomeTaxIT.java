package io.paradaux.treasury.services.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.util.Modules;
import io.paradaux.common.ProgressiveTaxBrackets;
import io.paradaux.common.TaxPeriod;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.guice.DatabaseModule;
import io.paradaux.treasury.model.config.ProgressiveIncomeTaxConfiguration;
import io.paradaux.treasury.model.economy.TransferRequest;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import io.paradaux.treasury.testsupport.TestServicesModule;
import io.paradaux.treasury.utils.Idempotency;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Runs real ledger transfers against MariaDB with the progressive income tax on
 * (0% to $5,000, then 3%), checking the tax lands in the same transaction as the
 * payment and the running total survives across payments.
 */
class ProgressiveIncomeTaxIT extends IntegrationTestBase {

    private LedgerService ledgerService;
    private AccountService accountService;
    private int taxAccountId;

    @BeforeEach
    void setUpEnabledIncomeTax() {
        this.injector = Guice.createInjector(
                new DatabaseModule(this.dataSource),
                Modules.override(new TestServicesModule()).with(new EnabledIncomeTaxModule()));
        ledgerService = injector.getInstance(LedgerService.class);
        accountService = injector.getInstance(AccountService.class);
        ledgerService.bootstrapGovernmentAccounts();
        taxAccountId = injector.getInstance(TaxApi.class).getDefaultTaxAccountId();
    }

    private int newPlayer() {
        return ledgerService.resolveOrCreatePersonal(UUID.randomUUID()).getAccountId();
    }

    private long pay(int from, int to, String amount, String plugin, byte[] dedup) {
        return ledgerService.transfer(new TransferRequest(
                from, to, new BigDecimal(amount), "test payment", UUID.randomUUID(), null, plugin, dedup));
    }

    @Test
    void startingBalanceIsNotIncome() {
        int player = newPlayer();
        assertThat(accountService.getBalanceReadOnly(player)).isEqualByComparingTo("10000.00");
        assertThat(accountService.getBalanceReadOnly(taxAccountId)).isEqualByComparingTo("0.00");
    }

    @Test
    void taxesOnlyTheIncomeAboveTheBracketFloor() {
        int payer = newPlayer();
        int earner = newPlayer();

        pay(payer, earner, "500.00", "Test", null);
        assertThat(accountService.getBalanceReadOnly(earner)).isEqualByComparingTo("10500.00");
        assertThat(accountService.getBalanceReadOnly(taxAccountId)).isEqualByComparingTo("0.00");

        // Period income 500 -> 5500: 3% of the 500 above 5000.
        pay(payer, earner, "5000.00", "Test", null);
        assertThat(accountService.getBalanceReadOnly(earner)).isEqualByComparingTo("15485.00");
        assertThat(accountService.getBalanceReadOnly(taxAccountId)).isEqualByComparingTo("15.00");

        // Already above the floor: 3% outright.
        pay(payer, earner, "1000.00", "Test", null);
        assertThat(accountService.getBalanceReadOnly(taxAccountId)).isEqualByComparingTo("45.00");
    }

    @Test
    void exemptPluginTransfersAreNotIncome() {
        int payer = newPlayer();
        int earner = newPlayer();

        pay(payer, earner, "9000.00", "Treasury-Pay", null);

        assertThat(accountService.getBalanceReadOnly(earner)).isEqualByComparingTo("19000.00");
        assertThat(accountService.getBalanceReadOnly(taxAccountId)).isEqualByComparingTo("0.00");
    }

    @Test
    void chestShopSalesAreNotIncomeByDefault() {
        int payer = newPlayer();
        int earner = newPlayer();

        pay(payer, earner, "9000.00", "ChestShop", null);

        assertThat(accountService.getBalanceReadOnly(earner)).isEqualByComparingTo("19000.00");
        assertThat(accountService.getBalanceReadOnly(taxAccountId)).isEqualByComparingTo("0.00");
    }

    @Test
    void aReplayedPaymentIsTaxedOnce() {
        int payer = newPlayer();
        int earner = newPlayer();
        byte[] dedup = Idempotency.sha256("income-tax-it:" + UUID.randomUUID());

        long first = pay(payer, earner, "6000.00", "Test", dedup);
        long replay = pay(payer, earner, "6000.00", "Test", dedup);

        assertThat(replay).isEqualTo(first);
        assertThat(accountService.getBalanceReadOnly(earner)).isEqualByComparingTo("15970.00");
        assertThat(accountService.getBalanceReadOnly(taxAccountId)).isEqualByComparingTo("30.00");
    }

    @Test
    void paymentsToGovernmentAccountsAreNotTaxed() {
        int payer = newPlayer();

        pay(payer, taxAccountId, "8000.00", "Test", null);

        assertThat(accountService.getBalanceReadOnly(taxAccountId)).isEqualByComparingTo("8000.00");
        assertThat(accountService.getBalanceReadOnly(payer)).isEqualByComparingTo("2000.00");
    }

    /** Enabled: 0% to $5,000, 3% above; starting-balances exempt; Treasury-Pay exempt. */
    static class EnabledIncomeTaxModule extends AbstractModule {
        @Provides
        @Singleton
        ProgressiveIncomeTaxConfiguration enabled() {
            Map<String, String> brackets = new LinkedHashMap<>();
            brackets.put("0", "0");
            brackets.put("5000", "0.03");
            return ProgressiveIncomeTaxConfiguration.forTesting(new ProgressiveIncomeTaxConfiguration.Snapshot(
                    true,
                    "DCGovernment",
                    TaxPeriod.of(Duration.ofDays(30), Instant.parse("2026-01-01T00:00:00Z")),
                    ProgressiveTaxBrackets.fromRawEntries(brackets, warning -> {}),
                    Set.of("starting-balances"),
                    Set.of("Treasury-Pay")));
        }
    }
}
