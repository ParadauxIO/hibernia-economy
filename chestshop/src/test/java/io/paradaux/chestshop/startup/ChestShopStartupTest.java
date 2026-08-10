package io.paradaux.chestshop.startup;

import com.google.inject.Injector;
import com.google.inject.Guice;
import com.google.inject.Module;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.commands.BypassCommand;
import io.paradaux.chestshop.commands.ChestShopCommand;
import io.paradaux.chestshop.commands.FindCommand;
import io.paradaux.chestshop.commands.GiveCommand;
import io.paradaux.chestshop.commands.ItemInfoCommand;
import io.paradaux.chestshop.commands.MetricsCommand;
import io.paradaux.chestshop.commands.ResyncCommand;
import io.paradaux.chestshop.commands.ShopInfoCommand;
import io.paradaux.chestshop.commands.ShopToggleCommand;
import io.paradaux.chestshop.commands.ToggleCommand;
import io.paradaux.chestshop.commands.VersionCommand;
import io.paradaux.chestshop.dialogs.FindDialog;
import io.paradaux.chestshop.guice.ChestShopModule;
import io.paradaux.chestshop.guice.DatabaseModule;
import io.paradaux.chestshop.integration.IntegrationRegistrar;
import io.paradaux.chestshop.listeners.BlockPlaceListener;
import io.paradaux.chestshop.listeners.ChestBreakListener;
import io.paradaux.chestshop.listeners.GarbageTextListener;
import io.paradaux.chestshop.listeners.ItemMoveListener;
import io.paradaux.chestshop.listeners.MarketListener;
import io.paradaux.chestshop.listeners.PaperBlockDestroyListener;
import io.paradaux.chestshop.listeners.PhysicsBreakListener;
import io.paradaux.chestshop.listeners.PlayerConnectListener;
import io.paradaux.chestshop.listeners.PlayerInteractListener;
import io.paradaux.chestshop.listeners.PlayerInventoryListener;
import io.paradaux.chestshop.listeners.PlayerTeleportListener;
import io.paradaux.chestshop.listeners.PreviewListener;
import io.paradaux.chestshop.listeners.RestrictedSignListener;
import io.paradaux.chestshop.listeners.SignBacksideProtectorListener;
import io.paradaux.chestshop.listeners.SignBreakListener;
import io.paradaux.chestshop.listeners.SignCreateListener;
import io.paradaux.chestshop.listeners.StockCounterListener;
import io.paradaux.hibernia.framework.guice.HiberniaModule;
import io.paradaux.hibernia.testsupport.HiberniaStartupAssertion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Startup-shaped test: builds ChestShop's <em>real</em> Guice module graph over a live
 * MockBukkit server and drives {@code CommandManager}/{@code ListenerManager}
 * {@code registerAll()} the way {@link ChestShop#onEnable()} does — so missing bindings,
 * route conflicts and command-tree errors surface here instead of only at server boot
 * (chestshop/testing/0002).
 *
 * <p>The plugin is <em>loaded</em> (data folder, config.yml/messages.properties, Paper
 * lifecycle) but not <em>enabled</em>. The module list mirrors {@code onEnable} exactly;
 * ChestShop's {@code DatabaseModule} is backed by SQLite, so it runs against two real
 * temp {@code .db} files (embedded, no server needed) rather than a mock.</p>
 */
class ChestShopStartupTest {

    private ServerMock server;
    private ChestShop plugin;

    @TempDir
    Path dbDir;

    @BeforeEach
    void boot() {
        server = MockBukkit.mock();
        // Load without enabling, so the real onEnable (integration hooks, market init) never runs.
        plugin = (ChestShop) server.getPluginManager().loadPlugin(ChestShop.class);
    }

    @AfterEach
    void shutdown() {
        MockBukkit.unmock();
    }

    @Test
    void realInjectorRegistersAllCommandsAndListeners() {
        // The exact HiberniaModule wiring ChestShop.onEnable() builds.
        HiberniaModule hibernia = HiberniaModule.forPlugin(plugin)
                .scanConfiguration("io.paradaux.chestshop.model.config")
                .handlers(
                        ChestShopCommand.class,
                        BypassCommand.class,
                        ItemInfoCommand.class,
                        ShopInfoCommand.class,
                        VersionCommand.class,
                        MetricsCommand.class,
                        GiveCommand.class,
                        ToggleCommand.class,
                        FindCommand.class,
                        ShopToggleCommand.class,
                        ResyncCommand.class)
                .dialogs(FindDialog.class)
                .listeners(
                        IntegrationRegistrar.class,
                        SignBreakListener.class,
                        PhysicsBreakListener.class,
                        PaperBlockDestroyListener.class,
                        ChestBreakListener.class,
                        SignCreateListener.class,
                        BlockPlaceListener.class,
                        SignBacksideProtectorListener.class,
                        MarketListener.class,
                        PlayerConnectListener.class,
                        PlayerInteractListener.class,
                        PlayerInventoryListener.class,
                        PlayerTeleportListener.class,
                        GarbageTextListener.class,
                        RestrictedSignListener.class,
                        StockCounterListener.class,
                        ItemMoveListener.class,
                        PreviewListener.class)
                .build();

        // Real SQLite-backed DatabaseModule over two temp .db files (embedded, no server).
        File usersDb = dbDir.resolve("users.db").toFile();
        File itemsDb = dbDir.resolve("items.db").toFile();

        List<Module> modules = List.of(
                hibernia,
                new ChestShopModule(plugin.getDataFolder()),
                new DatabaseModule(usersDb, itemsDb));

        Injector injector = Guice.createInjector(modules);
        HiberniaStartupAssertion.assertRegisters(injector);
    }
}
