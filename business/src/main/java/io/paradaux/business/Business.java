package io.paradaux.business;

import com.google.inject.Guice;
import com.google.inject.Injector;

import io.paradaux.hibernia.framework.commander.CommandManager;
import io.paradaux.hibernia.framework.events.ListenerManager;
import io.paradaux.hibernia.framework.guice.HiberniaModule;
import io.paradaux.business.api.BusinessApi;
import io.paradaux.business.commands.AccountCommands;
import io.paradaux.business.commands.ChatCommands;
import io.paradaux.business.commands.FirmCommands;
import io.paradaux.business.commands.HelpCommands;
import io.paradaux.business.commands.MiscCommands;
import io.paradaux.business.commands.ReloadCommand;
import io.paradaux.business.commands.RequestCommands;
import io.paradaux.business.commands.RoleCommands;
import io.paradaux.business.commands.SalesCommands;
import io.paradaux.business.commands.StaffCommands;
import io.paradaux.business.commands.TaxCommands;
import io.paradaux.business.guice.BusinessModule;
import io.paradaux.business.guice.DatabaseModule;
import io.paradaux.business.jobs.ExpireRequestsJob;
import io.paradaux.business.listeners.ChestShopSaleListener;
import io.paradaux.business.listeners.FirmBalanceTaxListener;
import io.paradaux.business.listeners.OnlineRosterListener;
import io.paradaux.business.model.config.DatabaseConfiguration;
import io.paradaux.business.model.config.FirmConfiguration;
import io.paradaux.business.services.FirmSalesNotificationService;
import io.paradaux.business.commands.resolvers.FirmNameResolver;
import io.paradaux.business.commands.resolvers.FirmPlayerResolver;
import io.paradaux.business.commands.resolvers.OnlineFirmNameResolver;
import io.paradaux.treasury.api.SalesQueryApi;
import io.paradaux.treasury.api.TreasuryApi;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

// Non-final so the startup test can load it under MockBukkit (which subclasses the
// plugin main to intercept lifecycle) and drive the real injector (business/testing/0002).
// The class is never extended in production; this only unblocks the wiring test.
public class Business extends JavaPlugin {

    private Injector injector;
    private ExpireRequestsJob expireRequestsJob;

    @Override
    public void onEnable() {
        getLogger().info("Loading configuration...");

        // 1) Build the HiberniaModule: it scans the config package, binds the
        //    plugin, the ConfigurationLoader, the discovered @ConfigurationComponents
        //    (DatabaseConfiguration), Message (eager), the command/resolver/listener
        //    multibinders, and the PAPI/dialog support.
        HiberniaModule hiberniaModule = HiberniaModule.forPlugin(this)
                .scanConfiguration("io.paradaux.business.model.config")
                .handlers(
                        AccountCommands.class,
                        FirmCommands.class,
                        HelpCommands.class,
                        MiscCommands.class,
                        RequestCommands.class,
                        RoleCommands.class,
                        StaffCommands.class,
                        ReloadCommand.class,
                        TaxCommands.class,
                        SalesCommands.class,
                        ChatCommands.class
                )
                .resolvers(
                        FirmPlayerResolver.class,
                        FirmNameResolver.class,
                        OnlineFirmNameResolver.class
                )
                .listeners(
                        FirmBalanceTaxListener.class,
                        ChestShopSaleListener.class,
                        OnlineRosterListener.class
                )
                .build();

        // Pull the typed DatabaseConfiguration out of the (already-loaded)
        // HiberniaModule so DatabaseModule can wire the DataSource pre-injector.
        DatabaseConfiguration dbCfg = hiberniaModule.configuration(DatabaseConfiguration.class);
        if (dbCfg == null) {
            throw new IllegalStateException("DatabaseConfiguration not found. Check @ConfigurationComponent and package scan.");
        }

        // Fail fast rather than boot against the shared money DB with the shipped
        // placeholder password — same guard Treasury has, so every writer to the
        // shared DB behaves identically (ADT default-db-creds-in-shipped-config).
        String dbPass = dbCfg.getPassword();
        if ("password".equals(dbPass) || "CHANGE_ME".equals(dbPass)) {
            throw new IllegalStateException(
                    "Refusing to start: the database password is still the placeholder default. "
                    + "Set database.password in config.yml.");
        }

        // 1b) Obtain TreasuryApi from Bukkit services (Treasury plugin must be loaded first)
        RegisteredServiceProvider<TreasuryApi> rsp =
                Bukkit.getServicesManager().getRegistration(TreasuryApi.class);
        if (rsp == null) {
            getLogger().severe("Treasury plugin not found! Business plugin requires Treasury.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        TreasuryApi treasuryApi = rsp.getProvider();

        // 1c) SalesQueryApi — the read path into Treasury's chestshop_sale tracker,
        // registered by the same Treasury plugin (PAR-176). Required for /firm sales.
        RegisteredServiceProvider<SalesQueryApi> salesRsp =
                Bukkit.getServicesManager().getRegistration(SalesQueryApi.class);
        if (salesRsp == null) {
            getLogger().severe("Treasury SalesQueryApi not found! Update Treasury to a version that provides it.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        SalesQueryApi salesQueryApi = salesRsp.getProvider();

        // 2) Create the injector, wiring:
        //    - HiberniaModule (plugin + config components + Message + command/listener wiring)
        //    - BusinessModule (services, Treasury APIs, hand-rolled config singletons)
        //    - DatabaseModule (needs the typed DatabaseConfiguration)
        getLogger().info("Setting up dependency injection...");
        this.injector = Guice.createInjector(
                hiberniaModule,
                new BusinessModule(this, treasuryApi, salesQueryApi),
                new DatabaseModule(dbCfg)
        );

        // 3) Register commands (DI-managed)
        injector.getInstance(CommandManager.class).registerAll();

        // Register jobs
        expireRequestsJob = injector.getInstance(ExpireRequestsJob.class);
        expireRequestsJob.schedule();

        // 4) Register public API
        BusinessApi api = injector.getInstance(BusinessApi.class);
        getServer().getServicesManager().register(BusinessApi.class, api, this, ServicePriority.Normal);

        // 5) Register events via the framework ListenerManager (FirmBalanceTaxListener,
        //    ChestShopSaleListener, OnlineRosterListener — declared in HiberniaModule.listeners(...) above).
        injector.getInstance(ListenerManager.class).registerAll();

        // 6) Drive the firm sale-notification digest: flush buffered sales on a
        // timer so bursts are condensed into one message per firm per window.
        FirmSalesNotificationService salesNotifications =
                injector.getInstance(FirmSalesNotificationService.class);
        long flushTicks = injector.getInstance(FirmConfiguration.class).getSalesNotifyFlushSeconds() * 20L;
        getServer().getScheduler().runTaskTimer(this, salesNotifications::flush, flushTicks, flushTicks);

        // 7) Register the employee-only firm chat channel with CarbonChat (PAR-20).
        // CarbonChat is a soft dependency: if it's absent, linking the Carbon-
        // referencing method throws NoClassDefFoundError at the call site (before
        // the method's own guard runs), so catch Throwable here to keep firm chat
        // optional without breaking enable.
        try {
            injector.getInstance(io.paradaux.business.chat.FirmChatService.class).initialise();
        } catch (Throwable t) {
            getLogger().info("CarbonChat unavailable — firm chat disabled (" + t.getClass().getSimpleName() + ").");
        }
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);

        // Stop any scheduled tasks (ExpireRequestsJob etc.) before tearing down
        // the DI graph so they can't run with a half-closed DataSource.
        getServer().getScheduler().cancelTasks(this);

        try {
            javax.sql.DataSource ds = injector.getInstance(javax.sql.DataSource.class);
            if (ds instanceof com.zaxxer.hikari.HikariDataSource hikari) hikari.close();
        } catch (Exception ignored) { }
        injector = null;
    }
}
