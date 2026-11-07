package io.paradaux.treasury.internal.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.internal.utils.TreasuryConstants;
import io.paradaux.treasury.model.economy.Account;
import io.paradaux.treasury.model.tax.TaxCollection;
import io.paradaux.treasury.model.tax.TaxResult;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import io.paradaux.treasury.utils.Idempotency;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaxApiCollectIT extends IntegrationTestBase {

    private TaxApi taxApi;
    private LedgerService ledgerService;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        taxApi = injector.getInstance(TaxApi.class);
        ledgerService = injector.getInstance(LedgerService.class);
        accountService = injector.getInstance(AccountService.class);
        ledgerService.bootstrapGovernmentAccounts();
    }

    @Test
    void collectTax_belowMinimum_returnsSkipped() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        Account playerAcc = accountService.getAccountByUUID(player);

        TaxResult result = taxApi.collectTax(TaxCollection.toDefaultAccount(
                playerAcc.getAccountId(),
                new BigDecimal("0.001"),
                "test-tax",
                "tiny",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                "test",
                null));

        assertThat(result).isInstanceOf(TaxResult.Skipped.class);
        // Player balance unchanged
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("10000.00");
    }

    @Test
    void collectTax_validAmount_routesToDefaultAccountAndCollects() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        Account playerAcc = accountService.getAccountByUUID(player);

        TaxResult result = taxApi.collectTax(TaxCollection.toDefaultAccount(
                playerAcc.getAccountId(),
                new BigDecimal("100.00"),
                "test-tax",
                "test",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                "test",
                null));

        assertThat(result).isInstanceOf(TaxResult.Collected.class);
        TaxResult.Collected c = (TaxResult.Collected) result;
        assertThat(c.amountCharged()).isEqualByComparingTo("100.00");

        // Player debited
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("9900.00");

        // Default tax account credited
        Account taxGov = accountService.getAccountById(taxApi.getDefaultTaxAccountId());
        assertThat(accountService.getBalanceReadOnly(taxGov.getAccountId())).isEqualByComparingTo("100.00");
    }

    @Test
    void collectTax_failedTransfer_returnsFailedAndDoesNotMoveMoney() {
        // Source account has 0 balance and no overdraft → debit fails
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        ledgerService.adminSet(player, BigDecimal.ZERO, "drain", UUID.randomUUID());
        Account playerAcc = accountService.getAccountByUUID(player);

        TaxResult result = taxApi.collectTax(TaxCollection.toDefaultAccount(
                playerAcc.getAccountId(),
                new BigDecimal("50.00"),
                "test-tax",
                "fails",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                "test",
                null));

        assertThat(result).isInstanceOf(TaxResult.Failed.class);
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void collectTax_replayWithSameDedupKey_doesNotDoubleCharge() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        Account playerAcc = accountService.getAccountByUUID(player);
        byte[] key = Idempotency.sha256("test-tax:dedup:" + player);

        taxApi.collectTax(TaxCollection.toDefaultAccount(
                playerAcc.getAccountId(),
                new BigDecimal("75.00"),
                "test-tax",
                "first",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                "test",
                key));
        taxApi.collectTax(TaxCollection.toDefaultAccount(
                playerAcc.getAccountId(),
                new BigDecimal("75.00"),
                "test-tax",
                "replay",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                "test",
                key));

        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("9925.00");
    }

    @Test
    void collectRateTax_computesAndRoutesProperly() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        Account playerAcc = accountService.getAccountByUUID(player);

        // 5 % tax on a 200.00 transaction = 10.00
        TaxResult result = taxApi.collectRateTax(
                playerAcc.getAccountId(),
                new BigDecimal("200.00"),
                new BigDecimal("0.05"),
                "transaction-tax",
                "5%",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                "test",
                null);

        assertThat(result).isInstanceOf(TaxResult.Collected.class);
        assertThat(((TaxResult.Collected) result).amountCharged()).isEqualByComparingTo("10.00");
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("9990.00");
    }

    @Test
    void applySourceIncomeTax_disabledByDefault_returnsSkipped() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);

        TaxResult result = taxApi.applySourceIncomeTax(player, new BigDecimal("100.00"), "Realty");

        assertThat(result).isInstanceOf(TaxResult.Skipped.class);
        assertThat(((TaxResult.Skipped) result).reason()).contains("disabled");
    }

    @Test
    void collectBatch_processesEachIndependently_preservesOrder() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        Account playerAcc = accountService.getAccountByUUID(player);

        List<TaxCollection> batch = List.of(
                TaxCollection.toDefaultAccount(
                        playerAcc.getAccountId(),
                        new BigDecimal("0.001"),
                        "small",
                        "below min",
                        TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                        "test",
                        null),
                TaxCollection.toDefaultAccount(
                        playerAcc.getAccountId(),
                        new BigDecimal("10.00"),
                        "ok",
                        "should pass",
                        TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                        "test",
                        null));

        List<TaxResult> results = taxApi.collectBatch(batch);

        assertThat(results).hasSize(2);
        assertThat(results.get(0)).isInstanceOf(TaxResult.Skipped.class);
        assertThat(results.get(1)).isInstanceOf(TaxResult.Collected.class);
    }

    @Test
    void registerCycleParticipant_delegatesToRegistry_andIsReadable() {
        taxApi.registerCycleParticipant("Realty", io.paradaux.treasury.model.tax.TaxCycleType.WEEKLY);
        assertThat(taxApi.getCycleParticipants(io.paradaux.treasury.model.tax.TaxCycleType.WEEKLY))
                .contains("Realty");
    }

    @Test
    void isCycleEnabled_reflectsCycleConfig() {
        // TestServicesModule installs taxCyclesAllDisabled
        assertThat(taxApi.isCycleEnabled(io.paradaux.treasury.model.tax.TaxCycleType.DAILY))
                .isFalse();
        assertThat(taxApi.isCycleEnabled(io.paradaux.treasury.model.tax.TaxCycleType.WEEKLY))
                .isFalse();
        assertThat(taxApi.isCycleEnabled(io.paradaux.treasury.model.tax.TaxCycleType.MONTHLY))
                .isFalse();
    }

    @Test
    void getDefaultTaxAccountName_andId_areConsistent() {
        String name = taxApi.getDefaultTaxAccountName();
        int id = taxApi.getDefaultTaxAccountId();
        Account acc = accountService.getAccountById(id);
        assertThat(acc.getDisplayName()).isEqualTo(name);
    }

    @Test
    void collectRateTax_explicitDestination_skipsBelowMinimum() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        Account playerAcc = accountService.getAccountByUUID(player);
        int destId = taxApi.getDefaultTaxAccountId();

        TaxResult result = taxApi.collectRateTax(
                playerAcc.getAccountId(),
                destId,
                new BigDecimal("0.10"), // 0.10 * 0.05 = 0.005, rounds to 0.00 → below minimum
                new BigDecimal("0.05"),
                "tiny-tax",
                "below threshold",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                "test",
                null);

        assertThat(result).isInstanceOf(TaxResult.Skipped.class);
    }

    @Test
    void collectRateTax_explicitDestination_collects() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        Account playerAcc = accountService.getAccountByUUID(player);
        int destId = taxApi.getDefaultTaxAccountId();

        TaxResult result = taxApi.collectRateTax(
                playerAcc.getAccountId(),
                destId,
                new BigDecimal("500.00"),
                new BigDecimal("0.04"),
                "rate-tax",
                "4 percent",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                "test",
                null);

        assertThat(result).isInstanceOf(TaxResult.Collected.class);
        assertThat(((TaxResult.Collected) result).amountCharged()).isEqualByComparingTo("20.00");
        assertThat(accountService.getBalanceByOwnerUuid(player)).isEqualByComparingTo("9980.00");
    }

    @Test
    void getNextFireTime_delegatesToRegistry() {
        // Default registry has no fire time set for any cycle.
        assertThat(taxApi.getNextFireTime(io.paradaux.treasury.model.tax.TaxCycleType.DAILY))
                .isEmpty();
    }

    @Test
    void collectRateTax_defaultDestination_skipsBelowMinimum() {
        UUID player = UUID.randomUUID();
        ledgerService.resolveOrCreatePersonal(player);
        Account playerAcc = accountService.getAccountByUUID(player);

        TaxResult result = taxApi.collectRateTax(
                playerAcc.getAccountId(),
                new BigDecimal("0.05"),
                new BigDecimal("0.05"), // 0.0025 → 0.00
                "tiny-tax",
                "below default",
                TreasuryConstants.VIRTUAL_TREASURY_INITIATOR,
                "test",
                null);

        assertThat(result).isInstanceOf(TaxResult.Skipped.class);
    }
}
