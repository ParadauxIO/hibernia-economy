package io.paradaux.treasury.services.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.paradaux.hibernia.framework.configurator.ConfigurationLoader;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.treasury.Treasury;
import io.paradaux.treasury.model.config.*;
import io.paradaux.treasury.services.ConfigPublishService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigReloadServiceImplTest {
    @TempDir
    Path directory;

    @Test
    void invalidLastConfigDoesNotPublishEarlierCandidatesOrReloadMessages() throws Exception {
        Files.writeString(directory.resolve("config.yml"), """
                salaries:
                  government-account: New
                tax:
                  source-income-tax:
                    default-rate: broken
                """);
        Treasury plugin = mock(Treasury.class);
        var previous = new YamlConfiguration();
        previous.set("salaries.government-account", "Old");
        when(plugin.getConfig()).thenReturn(previous);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        var salary = new SalaryConfiguration(plugin);
        var balance = new BalanceTaxConfiguration(plugin);
        var income = new SourceIncomeTaxConfiguration(plugin);
        var progressive = new ProgressiveIncomeTaxConfiguration(plugin);
        var oldSalary = salary.snapshot();
        var oldBalance = balance.snapshot();
        var oldIncome = income.snapshot();
        var oldProgressive = progressive.snapshot();
        Message message = mock(Message.class);
        ConfigPublishService publisher = mock(ConfigPublishService.class);
        var service = new ConfigReloadServiceImpl(
                plugin,
                message,
                mock(ConfigurationLoader.class),
                salary,
                balance,
                income,
                progressive,
                mock(LoggingConfiguration.class),
                publisher);
        assertThatIllegalArgumentException()
                .isThrownBy(service::reloadAll)
                .withMessageContaining("tax.source-income-tax.default-rate");
        assertThat(salary.snapshot()).isSameAs(oldSalary);
        assertThat(balance.snapshot()).isSameAs(oldBalance);
        assertThat(income.snapshot()).isSameAs(oldIncome);
        assertThat(progressive.snapshot()).isSameAs(oldProgressive);
        verify(plugin, never()).reloadConfig();
        verifyNoInteractions(message, publisher);
    }

    @Test
    void malformedYamlIsRejectedBeforeTouchingLiveConfiguration() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "salaries: [broken");
        Treasury plugin = mock(Treasury.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        var service = new ConfigReloadServiceImpl(
                plugin,
                mock(Message.class),
                mock(ConfigurationLoader.class),
                mock(SalaryConfiguration.class),
                mock(BalanceTaxConfiguration.class),
                mock(SourceIncomeTaxConfiguration.class),
                mock(ProgressiveIncomeTaxConfiguration.class),
                mock(LoggingConfiguration.class),
                mock(ConfigPublishService.class));
        assertThatIllegalArgumentException().isThrownBy(service::reloadAll);
        verify(plugin, never()).reloadConfig();
        verify(plugin, never()).getConfig();
    }

    @Test
    void validReloadPublishesCandidatesAndRetainsInjectedFrameworkInstances() throws Exception {
        Files.writeString(directory.resolve("config.yml"), """
                salaries:
                  government-account: New
                tax:
                  balance:
                    government-account: New
                  source-income-tax:
                    government-account: New
                    default-rate: 0.2
                  progressive-income-tax:
                    enabled: true
                    government-account: New
                    period: 7d
                logging:
                  level: INFO
                """);
        Treasury plugin = mock(Treasury.class);
        var previous = new YamlConfiguration();
        previous.setDefaults(new YamlConfiguration());
        when(plugin.getConfig()).thenReturn(previous);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        doAnswer(invocation -> {
                    when(plugin.getConfig())
                            .thenReturn(YamlConfiguration.loadConfiguration(
                                    directory.resolve("config.yml").toFile()));
                    return null;
                })
                .when(plugin)
                .reloadConfig();
        var salary = new SalaryConfiguration(plugin);
        var balance = new BalanceTaxConfiguration(plugin);
        var income = new SourceIncomeTaxConfiguration(plugin);
        var progressive = new ProgressiveIncomeTaxConfiguration(plugin);
        var logging = new LoggingConfiguration();
        var loader = mock(ConfigurationLoader.class);
        when(loader.getComponents()).thenReturn(java.util.Map.of(LoggingConfiguration.class, logging));
        Message message = mock(Message.class);
        // Publishing the docs copy is fail-open: a failed write must not fail the reload.
        ConfigPublishService publisher = mock(ConfigPublishService.class);
        doThrow(new IllegalStateException("economy_config missing"))
                .when(publisher)
                .publish();
        var service = new ConfigReloadServiceImpl(
                plugin, message, loader, salary, balance, income, progressive, logging, publisher);
        try (var levels = mockStatic(io.paradaux.treasury.utils.LoggingConfigurer.class)) {
            service.reloadAll();
            levels.verify(() -> io.paradaux.treasury.utils.LoggingConfigurer.apply("INFO"));
        }
        assertThat(salary.getGovernmentAccount()).isEqualTo("New");
        assertThat(balance.getGovernmentAccount()).isEqualTo("New");
        assertThat(income.getGovernmentAccount()).isEqualTo("New");
        assertThat(progressive.getGovernmentAccount()).isEqualTo("New");
        assertThat(progressive.snapshot().period().length()).isEqualTo(java.time.Duration.ofDays(7));
        assertThat(logging.getLevel()).isEqualTo("INFO");
        verify(message).reload();
        verify(publisher).publish();
    }
}
