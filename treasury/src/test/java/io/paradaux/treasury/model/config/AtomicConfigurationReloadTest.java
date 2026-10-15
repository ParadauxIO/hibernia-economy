package io.paradaux.treasury.model.config;

import io.paradaux.treasury.Treasury;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AtomicConfigurationReloadTest {
    private YamlConfiguration config(String account, String rate) {
        var config = new YamlConfiguration();
        config.set("salaries.enabled", true);
        config.set("salaries.government-account", account);
        config.set("salaries.amount.worker", "12");
        config.set("tax.balance.enabled", true);
        config.set("tax.balance.government-account", account);
        config.set("tax.balance.brackets.0", rate);
        config.set("tax.source-income-tax.enabled", true);
        config.set("tax.source-income-tax.government-account", account);
        config.set("tax.source-income-tax.default-rate", rate);
        return config;
    }

    @Test
    void retainedSnapshotsStayConsistentAcrossReload() throws Exception {
        Treasury plugin = mock(Treasury.class);
        when(plugin.getConfig()).thenReturn(config("Old", "0.1"));
        var salary = new SalaryConfiguration(plugin);
        var balance = new BalanceTaxConfiguration(plugin);
        var income = new SourceIncomeTaxConfiguration(plugin);
        var captured = new CountDownLatch(1);
        var published = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var reader = executor.submit(() -> {
                var s = salary.snapshot();
                var b = balance.snapshot();
                var i = income.snapshot();
                captured.countDown();
                assertThat(published.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(s.governmentAccount()).isEqualTo("Old");
                assertThat(b.governmentAccount()).isEqualTo("Old");
                assertThat(b.getWeeklyRate(BigDecimal.TEN)).isEqualByComparingTo("0.1");
                assertThat(i.governmentAccount()).isEqualTo("Old");
                assertThat(i.getEffectiveRate("Plugin")).isEqualByComparingTo("0.1");
                return true;
            });
            try {
                assertThat(captured.await(5, TimeUnit.SECONDS)).isTrue();
                when(plugin.getConfig()).thenReturn(config("New", "0.2"));
                salary.reload();
                balance.reload();
                income.reload();
            } finally {
                published.countDown();
            }
            assertThat(reader.get(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(salary.getGovernmentAccount()).isEqualTo("New");
        assertThat(balance.getWeeklyRate(BigDecimal.TEN)).isEqualByComparingTo("0.2");
        assertThat(income.getEffectiveRate("Plugin")).isEqualByComparingTo("0.2");
    }

    @Test
    void malformedReloadsKeepEveryFieldOfThePreviousSnapshot() {
        Treasury plugin = mock(Treasury.class);
        when(plugin.getConfig()).thenReturn(config("Old", "0.1"));
        var salary = new SalaryConfiguration(plugin);
        var balance = new BalanceTaxConfiguration(plugin);
        var income = new SourceIncomeTaxConfiguration(plugin);
        var oldSalary = salary.snapshot();
        var oldBalance = balance.snapshot();
        var oldIncome = income.snapshot();
        var invalid = config("New", "garbage");
        invalid.set("salaries.amount.worker", "-1");
        when(plugin.getConfig()).thenReturn(invalid);
        assertThatIllegalArgumentException().isThrownBy(salary::reload);
        assertThatIllegalArgumentException().isThrownBy(balance::reload);
        assertThatIllegalArgumentException().isThrownBy(income::reload);
        assertThat(salary.snapshot()).isSameAs(oldSalary);
        assertThat(balance.snapshot()).isSameAs(oldBalance);
        assertThat(income.snapshot()).isSameAs(oldIncome);
    }

    @Test
    void snapshotsDefensivelyCopyTheirMaps() {
        var amounts = new java.util.HashMap<>(Map.of("worker", BigDecimal.TEN));
        var salary = new SalaryConfiguration.Snapshot(true, "Gov", 900, amounts, true, "afk", "true");
        var income = new SourceIncomeTaxConfiguration.Snapshot(true, BigDecimal.ZERO, "Gov", amounts);
        amounts.clear();
        assertThat(salary.amounts()).hasSize(1);
        assertThat(income.pluginRates()).hasSize(1);
        assertThatExceptionOfType(UnsupportedOperationException.class)
                .isThrownBy(() -> salary.amounts().clear());
    }
}
