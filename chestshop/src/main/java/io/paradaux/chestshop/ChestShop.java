package io.paradaux.chestshop;
import lombok.extern.slf4j.Slf4j;

import io.paradaux.chestshop.dialogs.FindDialog;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.commands.GiveCommand;
import io.paradaux.chestshop.commands.ItemInfoCommand;
import io.paradaux.chestshop.commands.ShopInfoCommand;
import io.paradaux.chestshop.commands.ToggleCommand;
import io.paradaux.chestshop.commands.VersionCommand;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.listeners.MarketListener;
import io.paradaux.chestshop.listeners.BlockPlaceListener;
import io.paradaux.chestshop.listeners.ChestBreakListener;
import io.paradaux.chestshop.listeners.SignBreakListener;
import io.paradaux.chestshop.listeners.SignCreateListener;
import io.paradaux.chestshop.listeners.GarbageTextListener;
import io.paradaux.chestshop.listeners.ItemMoveListener;
import io.paradaux.chestshop.listeners.StockCounterListener;
import io.paradaux.chestshop.listeners.PlayerConnectListener;
import io.paradaux.chestshop.listeners.PlayerInteractListener;
import io.paradaux.chestshop.listeners.PlayerInventoryListener;
import io.paradaux.chestshop.listeners.PlayerTeleportListener;
import io.paradaux.chestshop.integration.IntegrationRegistrar;
import io.paradaux.chestshop.integration.WorldGuardIntegration;
import io.paradaux.chestshop.services.ItemCodeService;
import io.paradaux.chestshop.listeners.RestrictedSignListener;



import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Main file of the plugin
 *
 * @author Acrobot
 */
@Slf4j
public class ChestShop extends JavaPlugin {
    private static ChestShop plugin;
    private static Server server;
    private static PluginDescriptionFile description;
    private static final ExecutorService executorService = Executors.newCachedThreadPool();


    // Instance references the plugin main class resolves at enable for its own
    // orchestration (DB migration, account-cache load). Not a service locator — every
    // other class injects its collaborators (PAR-282, PAR-300).
    private ItemCodeService itemCodes;
    private io.paradaux.chestshop.services.AccountService accounts;

    private static File dataFolder;

    private com.google.inject.Injector injector;
    private io.paradaux.hibernia.framework.configurator.ConfigurationLoader configurationLoader;
    // The framework Configurator repopulates this same component instance in place on
    // reload() (identity preserved), so injected references stay valid and this field can
    // be captured once for the plugin's own metrics/logging reads (PAR-282).
    private ChestShopConfiguration config;

    public ChestShop() {
        dataFolder = getDataFolder();
        description = getDescription();
        server = getServer();
        plugin = this;
    }

    @Override
    public void onLoad() {
        // WorldGuard's custom allow-shop flag must be registered before WorldGuard enables
        // (onLoad) — this runs pre-Guice, so it's the one integration concern outside the
        // IntegrationRegistrar. Everything else hooks at enable via Integration#hook.
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            WorldGuardIntegration.registerFlag();
        }
    }

    @Override
    public void onEnable() {

        // HiberniaFramework DI: loads ChestShopConfiguration from config.yml (and
        // additively reconciles new default keys on upgrade). The framework also
        // owns command registration now (PAR-264): the seven ChestShop commands
        // are CommandHandlers registered through Brigadier via CommandManager.
        // The framework Message bean is bound (no withoutMessages()) so both the
        // commander's error feedback and ChestShop's own command/listener output
        // render from messages.properties via the injected framework Message bean.
        io.paradaux.hibernia.framework.guice.HiberniaModule hibernia =
                io.paradaux.hibernia.framework.guice.HiberniaModule.forPlugin(this)
                        .scanConfiguration("io.paradaux.chestshop.model.config")
                        .handlers(io.paradaux.chestshop.commands.ChestShopCommand.class,
                                io.paradaux.chestshop.commands.BypassCommand.class,
                                ItemInfoCommand.class, ShopInfoCommand.class, VersionCommand.class,
                                io.paradaux.chestshop.commands.MetricsCommand.class, GiveCommand.class,
                                ToggleCommand.class,
                                io.paradaux.chestshop.commands.FindCommand.class,
                                io.paradaux.chestshop.commands.ShopToggleCommand.class,
                                io.paradaux.chestshop.commands.ResyncCommand.class)
                        // Usher dialog handlers — the /find search flow (first Usher use).
                        .dialogs(io.paradaux.chestshop.dialogs.FindDialog.class)
                        // Bukkit entrypoints — Guice-constructed so each injects the
                        // services it needs (listener → service → persistence), and
                        // registered in one call via ListenerManager#registerAll (PAR-282).
                        .listeners(
                                IntegrationRegistrar.class,
                                SignBreakListener.class,
                                io.paradaux.chestshop.listeners.PhysicsBreakListener.class,
                                io.paradaux.chestshop.listeners.PaperBlockDestroyListener.class,
                                ChestBreakListener.class,
                                SignCreateListener.class,
                                BlockPlaceListener.class,
                                io.paradaux.chestshop.listeners.SignBacksideProtectorListener.class,
                                MarketListener.class,
                                PlayerConnectListener.class,
                                PlayerInteractListener.class,
                                PlayerInventoryListener.class,
                                PlayerTeleportListener.class,
                                GarbageTextListener.class,
                                RestrictedSignListener.class,
                                StockCounterListener.class,
                                ItemMoveListener.class,
                                io.paradaux.chestshop.listeners.PreviewListener.class)
                        .build();
        this.injector = com.google.inject.Guice.createInjector(hibernia,
                new io.paradaux.chestshop.guice.ChestShopModule(getDataFolder()),
                new io.paradaux.chestshop.guice.DatabaseModule(loadFile("users.db"), loadFile("items.db")));
        this.configurationLoader = injector.getInstance(
                io.paradaux.hibernia.framework.configurator.ConfigurationLoader.class);
        itemCodes = injector.getInstance(io.paradaux.chestshop.services.ItemCodeService.class);
        accounts = injector.getInstance(io.paradaux.chestshop.services.AccountService.class);

        injector.getInstance(io.paradaux.hibernia.framework.commander.CommandManager.class).registerAll();

        loadConfig();

        itemCodes.migrateIfNeeded();

        if (!injector.getInstance(IntegrationRegistrar.class).hookAll()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Register every Bukkit listener bound via HiberniaModule.listeners(...) above.
        injector.getInstance(io.paradaux.hibernia.framework.events.ListenerManager.class).registerAll();
        injector.getInstance(MarketService.class).init();

        // Optional: keep the shop registry clean when WorldEdit/FAWE bulk-removes
        // shops (WE bypasses Bukkit block events). Only touched when WE is present,
        // so its classes are never loaded otherwise.
        if (getServer().getPluginManager().getPlugin("WorldEdit") != null
                || getServer().getPluginManager().getPlugin("FastAsyncWorldEdit") != null) {
            try {
                new io.paradaux.chestshop.listeners.WorldEditShopCleanupListener(this, injector.getInstance(io.paradaux.chestshop.services.MarketService.class)).register();
            } catch (Throwable t) {
                log.warn("WorldEdit shop-cleanup adapter unavailable", t);
            }
        }


    }

    public void loadConfig() {
        // Re-read config.yml through the framework. reload() repopulates the same
        // ChestShopConfiguration component instance in place, so the reference cached
        // here (and every injected reference elsewhere) reflects the new values.
        configurationLoader.reload();
        config = configurationLoader.getComponent(ChestShopConfiguration.class);

        accounts.load();
    }

    public static File loadFile(String string) {
        File file = new File(dataFolder, string);

        return loadFile(file);
    }

    private static File loadFile(File file) {
        if (!file.exists()) {
            try {
                if (file.getParent() != null) {
                    file.getParentFile().mkdirs();
                }

                file.createNewFile();
            } catch (IOException e) {
                log.error("Unable to load file " + file.getName(), e);
            }
        }

        return file;
    }

    public void onDisable() {
        executorService.shutdown();
        try {
            executorService.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        if (injector != null) {
            injector.getInstance(io.paradaux.chestshop.guice.ChestShopDatabases.class).close();
        }
    }

    // The static ChestShop.<service>() locator is gone (PAR-282): every class takes its
    // collaborators through constructor DI. The plugin main class keeps private references
    // only for the few services it drives itself at enable time (migration, account load).

    public static File getFolder() {
        return dataFolder;
    }

    public static void logDebug(String message) {
        if (plugin.config != null && plugin.config.isDebug()) {
            log.info("[DEBUG] " + message);
        }
    }

    public static Server getBukkitServer() {
        return server;
    }

    public static String getVersion() {
        return description.getVersion();
    }

    public static String getPluginName() {
        return description.getName();
    }

    public static List<String> getDependencies() {
        return description.getSoftDepend();
    }

    public static ChestShop getPlugin() {
        return plugin;
    }


    public static <E extends Event> E callEvent(E event) {
        Bukkit.getPluginManager().callEvent(event);
        return event;
    }

    public static void runInAsyncThread(Runnable runnable) {
        executorService.submit(runnable);
    }
}
