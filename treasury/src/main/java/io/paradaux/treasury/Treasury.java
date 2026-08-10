package io.paradaux.treasury;

import com.google.inject.*;
import com.google.inject.Module;
import com.zaxxer.hikari.HikariDataSource;
import io.paradaux.hibernia.framework.commander.CommandManager;
import io.paradaux.hibernia.framework.events.ListenerManager;
import io.paradaux.hibernia.framework.guice.HiberniaModule;
import io.paradaux.treasury.commands.resolvers.PayTargetResolver;
import io.paradaux.treasury.commands.*;
import io.paradaux.treasury.events.FirstPlayerJoinEvent;
import io.paradaux.treasury.events.OnlinePlayerRosterListener;
import io.paradaux.treasury.events.PlayerLoginListener;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import io.paradaux.treasury.adapters.VaultEconomyRegistrar;
import io.paradaux.treasury.api.MarketApi;
import io.paradaux.treasury.api.SalesQueryApi;
import io.paradaux.treasury.api.ShopQueryApi;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.api.impl.MarketApiImpl;
import io.paradaux.treasury.api.impl.SalesQueryApiImpl;
import io.paradaux.treasury.api.impl.ShopQueryApiImpl;
import io.paradaux.treasury.api.impl.TaxApiImpl;
import io.paradaux.treasury.api.impl.TreasuryApiImpl;
import io.paradaux.common.DataSourceProvider;
import io.paradaux.treasury.guice.*;
import io.paradaux.treasury.model.config.DatabaseConfiguration;
import io.paradaux.treasury.model.config.GovernmentConfiguration;
import io.paradaux.treasury.model.config.LoggingConfiguration;
import io.paradaux.treasury.model.config.SalaryConfiguration;
import io.paradaux.treasury.model.config.TaxCycleConfiguration;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.SalaryService;
import io.paradaux.treasury.services.TaxCycleRegistry;
import io.paradaux.treasury.services.TaxWebhookService;
import io.paradaux.treasury.tasks.SalaryTask;
import io.paradaux.treasury.tasks.TaxCycleTask;
import io.paradaux.treasury.utils.LoggingConfigurer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

@Slf4j
// Non-final so the startup test can load it under MockBukkit (which subclasses the
// plugin main to intercept lifecycle) and drive the real injector (treasury/testing/0001).
// The class is never extended in production; this only unblocks the wiring test.
public class Treasury extends JavaPlugin {

    @Getter
    private Injector injector;

    @Override
    public void onEnable() {
        // 1) Build the framework module. HiberniaModule scans the config package and
        //    binds the plugin, ConfigurationLoader, every @ConfigurationComponent,
        //    Message, the command/resolver/listener multibinders, PapiSupport and the
        //    dialog renderer. We fetch the configs needed for early bootstrap (log
        //    level, DataSource) off the module before the injector exists.
        HiberniaModule hiberniaModule = HiberniaModule.forPlugin(this)
                .scanConfiguration("io.paradaux.treasury.model.config")
                .handlers(
                        TreasuryCommand.class,
                        PayCommand.class,
                        PayAccountCommand.class,
                        BalanceCommand.class,
                        BaltopCommand.class,
                        EconomyCommand.class,
                        SalesCommand.class,
                        TransactionsCommand.class,
                        EcoCommand.class,
                        GovCommand.class,
                        FineCommand.class,
                        TaxCommand.class)
                .resolvers(PayTargetResolver.class)
                .listeners(
                        FirstPlayerJoinEvent.class,
                        PlayerLoginListener.class,
                        OnlinePlayerRosterListener.class)
                .build();

        // Apply the configured log level before anything else spams the console.
        LoggingConfiguration logCfg = hiberniaModule.configuration(LoggingConfiguration.class);
        if (logCfg != null) {
            LoggingConfigurer.apply(logCfg.getLevel());
        }

        log.info("Loading Treasury…");

        DatabaseConfiguration dbCfg = hiberniaModule.configuration(DatabaseConfiguration.class);
        if (dbCfg == null) {
            throw new IllegalStateException(
                    "DatabaseConfiguration not found. Check @ConfigurationComponent and package scan.");
        }

        // Fail fast instead of silently booting against the shared money DB with the
        // documented default password — the guard treasury-api-plugin already has,
        // back-ported here so all writers to the shared DB behave the same (ADT-187).
        String dbPass = dbCfg.getPassword();
        if ("password".equals(dbPass) || "CHANGE_ME".equals(dbPass)) {
            throw new IllegalStateException(
                    "Refusing to start: the database password is still the placeholder default. "
                    + "Set database.password in config.yml.");
        }

        // 2) Build the Guice injector. VaultModule is only installed when Vault
        //    is on the classpath, so VaultEconomyAdapter is bound conditionally.
        DataSource dataSource = DataSourceProvider.builder(
                        dbCfg.getHost(),
                        Integer.parseInt(dbCfg.getPort()),
                        dbCfg.getDatabase(),
                        dbCfg.getUsername(),
                        dbCfg.getPassword())
                .poolName("Treasury-Hikari")
                .maximumPoolSize(Integer.parseInt(dbCfg.getPoolMaximumSize()))
                .minimumIdle(Integer.parseInt(dbCfg.getPoolMinimumIdle()))
                .connectionTimeoutMs(Long.parseLong(dbCfg.getPoolConnectionTimeoutMs()))
                .maxLifetimeMs(Long.parseLong(dbCfg.getPoolMaxLifetimeMs()))
                .keepaliveMs(Long.parseLong(dbCfg.getPoolKeepaliveMs()))
                .leakDetectionThresholdMs(30_000L)
                .statementCaching(true)
                .build()
                .get();

        List<Module> modules = new ArrayList<>();
        modules.add(hiberniaModule);
        modules.add(new TreasuryModule(this));
        modules.add(new DatabaseModule(dataSource));
        if (isVaultAvailable()) {
            modules.add(new VaultModule());
        }
        this.injector = Guice.createInjector(modules);

        // 3) Bootstrap primitive GOVERNMENT accounts (starting-balances, tax-income, fines).
        GovernmentConfiguration govCfg = injector.getInstance(GovernmentConfiguration.class);
        log.info("Government accounts: starting-balances='{}', tax-income='{}', fines='{}'",
                govCfg.getStartingBalancesAccount(),
                govCfg.getTaxIncomeAccount(),
                govCfg.getFinesAccount());
        injector.getInstance(LedgerService.class).bootstrapGovernmentAccounts();

        // 4) Register commands.
        injector.getInstance(CommandManager.class).registerAll();

        // 5) Register listeners (Set<Listener> bound via HiberniaModule.listeners(...)).
        injector.getInstance(ListenerManager.class).registerAll();

        // 6) Register Vault economy if available.
        if (isVaultAvailable()) {
            VaultEconomyRegistrar.register(this, injector);
        } else {
            log.info("Vault not found. Vault economy provider will not be registered.");
        }

        // 7) Register Treasury and Tax APIs as Bukkit services.
        registerTreasuryApi();
        registerTaxApi();
        registerMarketApi();
        registerSalesQueryApi();
        registerShopQueryApi();
        scheduleSalaries();

        log.info("Treasury enabled.");
    }

    private void scheduleSalaries() {
        SalaryConfiguration salaryCfg = injector.getInstance(SalaryConfiguration.class);
        if (!salaryCfg.isEnabled()) {
            return;
        }
        SalaryService salaryService = injector.getInstance(SalaryService.class);
        new SalaryTask(this, salaryService).schedule(salaryCfg.getIntervalSeconds());
        log.info("Government salaries enabled (every {}s, {} group(s), from {})",
                salaryCfg.getIntervalSeconds(), salaryCfg.getAmounts().size(), salaryCfg.getGovernmentAccount());
    }

    @Override
    public void onDisable() {
        if (injector != null) {
            DataSource ds = injector.getInstance(DataSource.class);
            if (ds instanceof HikariDataSource hds) {
                hds.close();
            }
        }
        log.info("Treasury disabled.");
    }

    // ---- Helpers ----

    private void registerTreasuryApi() {
        TreasuryApi api = injector.getInstance(TreasuryApiImpl.class);
        var existing = Bukkit.getServicesManager().getRegistration(TreasuryApi.class);
        if (existing != null) {
            log.info("Replacing existing TreasuryApi provider: {}", existing.getPlugin().getName());
            Bukkit.getServicesManager().unregister(existing.getProvider());
        }
        Bukkit.getServicesManager().register(TreasuryApi.class, api, this, ServicePriority.Highest);
    }

    private void registerTaxApi() {
        TaxApi taxApi = injector.getInstance(TaxApiImpl.class);
        Bukkit.getServicesManager().register(TaxApi.class, taxApi, this, ServicePriority.Highest);

        TaxCycleConfiguration cycleCfg = injector.getInstance(TaxCycleConfiguration.class);
        TaxCycleRegistry registry = injector.getInstance(TaxCycleRegistry.class);
        TaxWebhookService webhook = injector.getInstance(TaxWebhookService.class);

        if (cycleCfg.isDailyEnabled()) {
            TaxCycleTask.daily(this, taxApi, registry, webhook, cycleCfg.getDailyHour()).scheduleNext();
            log.info("Daily tax cycle enabled (hour={})", cycleCfg.getDailyHour());
        }
        if (cycleCfg.isWeeklyEnabled()) {
            TaxCycleTask.weekly(this, taxApi, registry, webhook,
                    cycleCfg.getWeeklyHour(), cycleCfg.getWeeklyDayOfWeek()).scheduleNext();
            log.info("Weekly tax cycle enabled (day={}, hour={})",
                    cycleCfg.getWeeklyDayOfWeek(), cycleCfg.getWeeklyHour());
        }
        if (cycleCfg.isMonthlyEnabled()) {
            TaxCycleTask.monthly(this, taxApi, registry, webhook,
                    cycleCfg.getMonthlyHour(), cycleCfg.getMonthlyDayOfMonth()).scheduleNext();
            log.info("Monthly tax cycle enabled (day-of-month={}, hour={})",
                    cycleCfg.getMonthlyDayOfMonth(), cycleCfg.getMonthlyHour());
        }
    }

    private void registerMarketApi() {
        MarketApi marketApi = injector.getInstance(MarketApiImpl.class);
        Bukkit.getServicesManager().register(MarketApi.class, marketApi, this, ServicePriority.Highest);
    }

    private void registerSalesQueryApi() {
        SalesQueryApi salesQueryApi = injector.getInstance(SalesQueryApiImpl.class);
        Bukkit.getServicesManager().register(SalesQueryApi.class, salesQueryApi, this, ServicePriority.Highest);
    }

    private void registerShopQueryApi() {
        ShopQueryApi shopQueryApi = injector.getInstance(ShopQueryApiImpl.class);
        Bukkit.getServicesManager().register(ShopQueryApi.class, shopQueryApi, this, ServicePriority.Highest);
    }

    private boolean isVaultAvailable() {
        try {
            Class.forName("net.milkbowl.vault.economy.Economy");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
