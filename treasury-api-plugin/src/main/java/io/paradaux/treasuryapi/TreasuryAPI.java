package io.paradaux.treasuryapi;

import com.google.inject.Guice;
import com.google.inject.Injector;
import io.paradaux.common.JwtKeys;
import io.paradaux.hibernia.framework.commander.CommandManager;
import io.paradaux.hibernia.framework.guice.HiberniaModule;
import io.paradaux.business.api.BusinessApi;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasuryapi.commands.TreasuryAPICommand;
import io.paradaux.treasuryapi.guice.DatabaseModule;
import io.paradaux.treasuryapi.guice.TreasuryAPIModule;
import io.paradaux.treasuryapi.model.config.ApiConfiguration;
import io.paradaux.treasuryapi.model.config.DatabaseConfiguration;
import io.paradaux.treasuryapi.model.config.ReconciliationConfiguration;
import io.paradaux.treasuryapi.tasks.GroupReconciliationTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class TreasuryAPI extends JavaPlugin {

    private Injector injector;

    @Override
    public void onEnable() {
        getLogger().info("Loading configuration...");

        // 1) Build the framework module — scans @ConfigurationComponents, binds the plugin,
        //    ConfigurationLoader, Message (eager), PlaceholderAPI support and the command
        //    multibinder (TreasuryAPICommand, which also carries /treasuryapi help).
        HiberniaModule hibernia = HiberniaModule.forPlugin(this)
                .scanConfiguration("io.paradaux.treasuryapi.model.config")
                .handlers(TreasuryAPICommand.class)
                .build();

        DatabaseConfiguration dbCfg = hibernia.configuration(DatabaseConfiguration.class);
        if (dbCfg == null) {
            throw new IllegalStateException("DatabaseConfiguration not found. Check @ConfigurationComponent and package scan.");
        }

        // 1b) Refuse to mint tokens with a placeholder or too-short signing secret.
        //     The Treasury REST API that verifies these tokens hard-fails on the
        //     same placeholder and on <32 chars; signing with the public default
        //     would let anyone who reads it forge admin/government JWTs.
        ApiConfiguration apiCfg = hibernia.configuration(ApiConfiguration.class);
        String jwtSecret = apiCfg != null ? apiCfg.getJwtSecret() : null;
        if (jwtSecret == null || jwtSecret.isBlank()
                || jwtSecret.equals("change-me-please-use-a-long-random-secret-key")
                || jwtSecret.length() < 32) {
            getLogger().severe("api.jwt-secret is unset, the placeholder default, or shorter than 32 characters. "
                    + "Set a long random secret matching the Treasury REST API before enabling. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        // A `base64:` secret can pass the length check above (its raw string is long)
        // yet decode to fewer than 32 bytes, which JwtKeys rejects only at the first
        // mint. Validate the actual derivation here so a mis-sized key disables the
        // plugin at boot rather than failing a player command later
        // (ADT base64-secret-passes-startup-guard-but-crashes-mint).
        try {
            JwtKeys.deriveHmacKey(jwtSecret);
        } catch (RuntimeException e) {
            getLogger().severe("api.jwt-secret could not derive a valid signing key: " + e.getMessage()
                    + " (a base64: secret must decode to at least 32 bytes). Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 2) Obtain TreasuryApi from Bukkit services (Treasury must be loaded first)
        RegisteredServiceProvider<TreasuryApi> treasuryRsp =
                Bukkit.getServicesManager().getRegistration(TreasuryApi.class);
        if (treasuryRsp == null) {
            getLogger().severe("Treasury plugin not found! This plugin requires Treasury.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        TreasuryApi treasuryApi = treasuryRsp.getProvider();

        // 3) Obtain BusinessApi from Bukkit services (Business must be loaded first)
        RegisteredServiceProvider<BusinessApi> businessRsp =
                Bukkit.getServicesManager().getRegistration(BusinessApi.class);
        if (businessRsp == null) {
            getLogger().severe("Business plugin not found! This plugin requires Business.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        BusinessApi businessApi = businessRsp.getProvider();

        // 4) Create the injector. HiberniaModule owns the framework bindings;
        //    TreasuryAPIModule adds the economy APIs and plugin services.
        getLogger().info("Setting up dependency injection...");
        this.injector = Guice.createInjector(
                hibernia,
                new TreasuryAPIModule(this, treasuryApi, businessApi),
                new DatabaseModule(dbCfg)
        );

        // 5) Register commands (/treasuryapi … and /treasuryapi help).
        injector.getInstance(CommandManager.class).registerAll();

        // 6) Schedule the LuckPerms → explorer-group reconciliation cron (opt-in,
        //    requires LuckPerms). Check the plugin by name so the LuckPerms class
        //    is never referenced when it is absent.
        ReconciliationConfiguration reconCfg = hibernia.configuration(ReconciliationConfiguration.class);
        if (reconCfg != null && reconCfg.isEnabled()) {
            if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
                injector.getInstance(GroupReconciliationTask.class).schedule(reconCfg.getIntervalSeconds());
                getLogger().info("Group reconciliation cron scheduled (every "
                        + reconCfg.getIntervalSeconds() + "s).");
            } else {
                getLogger().warning("reconciliation.enabled=true but LuckPerms is not installed — reconciliation skipped.");
            }
        }

        getLogger().info("TreasuryAPI plugin enabled.");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);

        try {
            // injector is null if onEnable aborted before building it (ADT-39) —
            // guard so disable during a failed startup doesn't NPE over the error.
            if (injector != null) {
                javax.sql.DataSource ds = injector.getInstance(javax.sql.DataSource.class);
                if (ds instanceof com.zaxxer.hikari.HikariDataSource hikari) hikari.close();
            }
        } catch (Exception ignored) { }
        injector = null;
    }
}
