package io.paradaux.treasury.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.hibernia.framework.configurator.ConfigurationLoader;
import io.paradaux.hibernia.framework.configurator.ConfigurationProcessor;
import io.paradaux.hibernia.framework.i18n.Message;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.Treasury;
import io.paradaux.treasury.model.config.BalanceTaxConfiguration;
import io.paradaux.treasury.model.config.LoggingConfiguration;
import io.paradaux.treasury.model.config.SalaryConfiguration;
import io.paradaux.treasury.model.config.SourceIncomeTaxConfiguration;
import io.paradaux.treasury.services.ConfigReloadService;
import io.paradaux.treasury.utils.LoggingConfigurer;

/**
 * Default {@link ConfigReloadService}: re-reads the config files and refreshes the
 * live config objects in place for {@code /treasury reload}. See {@link ConfigReloadService}
 * for what is and isn't reloaded.
 */
@Slf4j
@Singleton
public class ConfigReloadServiceImpl implements ConfigReloadService {

    private final Treasury plugin;
    private final Message message;
    private final ConfigurationLoader configurationLoader;
    private final SalaryConfiguration salaryConfiguration;
    private final BalanceTaxConfiguration balanceTaxConfiguration;
    private final SourceIncomeTaxConfiguration sourceIncomeTaxConfiguration;
    private final LoggingConfiguration loggingConfiguration;

    @Inject
    public ConfigReloadServiceImpl(Treasury plugin,
                                   Message message,
                                   ConfigurationLoader configurationLoader,
                                   SalaryConfiguration salaryConfiguration,
                                   BalanceTaxConfiguration balanceTaxConfiguration,
                                   SourceIncomeTaxConfiguration sourceIncomeTaxConfiguration,
                                   LoggingConfiguration loggingConfiguration) {
        this.plugin = plugin;
        this.message = message;
        this.configurationLoader = configurationLoader;
        this.salaryConfiguration = salaryConfiguration;
        this.balanceTaxConfiguration = balanceTaxConfiguration;
        this.sourceIncomeTaxConfiguration = sourceIncomeTaxConfiguration;
        this.loggingConfiguration = loggingConfiguration;
    }

    @Override
    public void reloadAll() {
        // 1) Re-read the files from disk.
        plugin.reloadConfig();
        message.reload();

        // 2) Re-populate the @ConfigurationComponent singletons in place (same
        //    instances injected into services), reading the now-fresh config.
        //
        //    INTENTIONAL — do NOT "simplify" this to ConfigurationLoader.reload().
        //    Under hibernia-framework 1.2.0, ConfigurationLoader.reload() constructs
        //    *fresh* component instances; the existing singletons that Guice already
        //    injected into every service would then be stale (services would keep
        //    reading the old objects while the loader's map points at new ones). By
        //    re-running ConfigurationProcessor over the live component instances we
        //    mutate the exact objects the injector handed out, so the reload is
        //    visible everywhere without rebuilding the injector.
        ConfigurationProcessor processor = new ConfigurationProcessor(plugin);
        configurationLoader.getComponents().values().forEach(processor::process);

        // 3) Refresh the manually-parsed config POJOs.
        salaryConfiguration.reload();
        balanceTaxConfiguration.reload();
        sourceIncomeTaxConfiguration.reload();

        // 4) Re-apply startup side effects that are cheap and safe to redo.
        LoggingConfigurer.apply(loggingConfiguration.getLevel());

        log.info("Configuration reloaded (config.yml + messages.properties).");
    }
}
