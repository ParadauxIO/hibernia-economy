package io.paradaux.treasury.model.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.paradaux.treasury.Treasury;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ProgressiveIncomeTaxConfigurationTest {

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(text);
        return cfg;
    }

    private static ProgressiveIncomeTaxConfiguration load(String text) throws InvalidConfigurationException {
        Treasury plugin = mock(Treasury.class);
        when(plugin.getConfig()).thenReturn(yaml(text));
        return new ProgressiveIncomeTaxConfiguration(plugin);
    }

    @Test
    void readsAFullSection() throws Exception {
        var cfg = load("""
                tax:
                  progressive-income-tax:
                    enabled: true
                    government-account: IncomeTax
                    period: 2w
                    period-anchor: 2026-03-01
                    brackets:
                      0: 0.0
                      5000: 0.03
                    exempt-source-accounts: [Starting-Balances]
                    exempt-plugins: [Treasury-Pay]
                """).snapshot();

        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.isActive()).isTrue();
        assertThat(cfg.governmentAccount()).isEqualTo("IncomeTax");
        assertThat(cfg.period().length()).isEqualTo(Duration.ofDays(14));
        assertThat(cfg.period().anchor()).isEqualTo(Instant.parse("2026-03-01T00:00:00Z"));
        assertThat(cfg.brackets().asMap()).containsEntry(new BigDecimal("5000"), new BigDecimal("0.03"));
        assertThat(cfg.isExemptSourceAccount("starting-balances")).isTrue();
        assertThat(cfg.isExemptSourceAccount("Eco")).isFalse();
        assertThat(cfg.isExemptSourceAccount(null)).isFalse();
        assertThat(cfg.isExemptPlugin("TREASURY-PAY")).isTrue();
        assertThat(cfg.isExemptPlugin(null)).isFalse();
    }

    @Test
    void defaultsWhenTheSectionIsMissing() throws Exception {
        var cfg = load("tax: {}").snapshot();

        assertThat(cfg.enabled()).isFalse();
        assertThat(cfg.isActive()).isFalse();
        assertThat(cfg.governmentAccount()).isEqualTo("DCGovernment");
        assertThat(cfg.period().length()).isEqualTo(Duration.ofDays(30));
        assertThat(cfg.brackets().isEmpty()).isTrue();
        assertThat(cfg.isExemptSourceAccount("starting-balances")).isTrue();
        assertThat(cfg.isExemptSourceAccount("eco")).isTrue();
        assertThat(cfg.exemptPlugins()).isEmpty();
    }

    @Test
    void byDefaultPlayerPaymentsAreIncomeAndChestShopSalesAreNot() throws Exception {
        var cfg = load("tax: {}").snapshot();
        assertThat(cfg.taxPlayerPayments()).isTrue();
        assertThat(cfg.taxChestShopSales()).isFalse();
        assertThat(cfg.isExemptPlugin("Treasury-Pay")).isFalse();
        assertThat(cfg.isExemptPlugin("chestshop")).isTrue();
    }

    @Test
    void theSwitchesCanBeFlipped() throws Exception {
        var cfg = load("""
                tax:
                  progressive-income-tax:
                    tax-player-payments: false
                    tax-chestshop-sales: true
                """).snapshot();
        assertThat(cfg.isExemptPlugin("treasury-pay")).isTrue();
        assertThat(cfg.isExemptPlugin("ChestShop")).isFalse();
        assertThat(cfg.isExemptPlugin("Realty")).isFalse();
    }

    @Test
    void reloadRejectsANonBooleanSwitch() throws Exception {
        var cfg = load("tax: {}");
        var candidate = yaml("""
                tax:
                  progressive-income-tax:
                    tax-chestshop-sales: sometimes
                """);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> cfg.prepareReload(candidate))
                .withMessageContaining("tax-chestshop-sales");
    }

    @Test
    void anEmptyExemptListOverridesTheDefaults() throws Exception {
        var cfg = load("""
                tax:
                  progressive-income-tax:
                    exempt-source-accounts: []
                """).snapshot();
        assertThat(cfg.exemptSourceAccounts()).isEmpty();
    }

    @Test
    void blankGovernmentAccountFallsBack() throws Exception {
        var cfg = load("""
                tax:
                  progressive-income-tax:
                    government-account: "  "
                """);
        assertThat(cfg.getGovernmentAccount()).isEqualTo("DCGovernment");
    }

    @Test
    void anInvalidPeriodAtStartupDisablesTheTax() throws Exception {
        var cfg = load("""
                tax:
                  progressive-income-tax:
                    enabled: true
                    period: sometimes
                    brackets:
                      0: 0.1
                """);
        assertThat(cfg.isEnabled()).isFalse();
        assertThat(cfg.snapshot().period().length()).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void enabledWithoutPositiveBracketsIsInactive() throws Exception {
        var cfg = load("""
                tax:
                  progressive-income-tax:
                    enabled: true
                    brackets:
                      0: 0.0
                """).snapshot();
        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.isActive()).isFalse();
    }

    @Test
    void reloadRejectsAnInvalidPeriod() throws Exception {
        var cfg = load("tax: {}");
        var candidate = yaml("""
                tax:
                  progressive-income-tax:
                    period: 0d
                """);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> cfg.prepareReload(candidate))
                .withMessageContaining("tax.progressive-income-tax");
    }

    @Test
    void reloadRejectsAnInvalidAnchor() throws Exception {
        var cfg = load("tax: {}");
        var candidate = yaml("""
                tax:
                  progressive-income-tax:
                    period-anchor: whenever
                """);
        assertThatIllegalArgumentException().isThrownBy(() -> cfg.prepareReload(candidate));
    }

    @Test
    void reloadRejectsAnOutOfRangeRate() throws Exception {
        var cfg = load("tax: {}");
        var candidate = yaml("""
                tax:
                  progressive-income-tax:
                    brackets:
                      0: 1.5
                """);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> cfg.prepareReload(candidate))
                .withMessageContaining("tax.progressive-income-tax.brackets");
    }

    @Test
    void reloadRejectsAnExemptListThatIsNotAList() throws Exception {
        var cfg = load("tax: {}");
        var candidate = yaml("""
                tax:
                  progressive-income-tax:
                    exempt-plugins: Treasury-Pay
                """);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> cfg.prepareReload(candidate))
                .withMessageContaining("exempt-plugins");
    }

    @Test
    void reloadRejectsAnExemptListWithNonNames() throws Exception {
        var cfg = load("tax: {}");
        var candidate = yaml("""
                tax:
                  progressive-income-tax:
                    exempt-source-accounts: [1, 2]
                """);
        assertThatIllegalArgumentException().isThrownBy(() -> cfg.prepareReload(candidate));
    }

    @Test
    void reloadReadsTheConfigAgain() throws Exception {
        Treasury plugin = mock(Treasury.class);
        when(plugin.getConfig()).thenReturn(yaml("tax: {}"));
        var cfg = new ProgressiveIncomeTaxConfiguration(plugin);
        when(plugin.getConfig()).thenReturn(yaml("""
                tax:
                  progressive-income-tax:
                    enabled: true
                """));
        cfg.reload();
        assertThat(cfg.isEnabled()).isTrue();
    }

    @Test
    void testInstancesIgnoreReloadAndRejectNullPublishes() {
        var snapshot = ProgressiveIncomeTaxConfiguration.forTesting(new ProgressiveIncomeTaxConfiguration.Snapshot(
                true,
                "Gov",
                io.paradaux.common.TaxPeriod.of(Duration.ofDays(1), Instant.EPOCH),
                io.paradaux.common.ProgressiveTaxBrackets.fromRawEntries(java.util.Map.of(), w -> {}),
                java.util.Set.of(" Eco ", ""),
                java.util.Set.of()));
        snapshot.reload();
        assertThat(snapshot.isEnabled()).isTrue();
        assertThat(snapshot.snapshot().exemptSourceAccounts()).containsExactly("eco");
        assertThatNullPointerException().isThrownBy(() -> snapshot.publish(null));
        assertThatNullPointerException().isThrownBy(() -> ProgressiveIncomeTaxConfiguration.forTesting(null));
    }
}
