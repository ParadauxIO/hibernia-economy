package io.paradaux.business.startup;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.util.Modules;
import io.paradaux.business.Business;
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
import io.paradaux.business.commands.resolvers.FirmNameResolver;
import io.paradaux.business.commands.resolvers.FirmPlayerResolver;
import io.paradaux.business.commands.resolvers.OnlineFirmNameResolver;
import io.paradaux.business.guice.BusinessModule;
import io.paradaux.business.guice.DatabaseModule;
import io.paradaux.business.listeners.ChestShopSaleListener;
import io.paradaux.business.listeners.FirmBalanceTaxListener;
import io.paradaux.business.model.config.DatabaseConfiguration;
import io.paradaux.hibernia.framework.guice.HiberniaModule;
import io.paradaux.hibernia.testsupport.HiberniaStartupAssertion;
import io.paradaux.treasury.api.SalesQueryApi;
import io.paradaux.treasury.api.TreasuryApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import javax.sql.DataSource;

import static org.mockito.Mockito.mock;

/**
 * Startup-shaped test: builds Business's <em>real</em> Guice module graph over a live
 * MockBukkit server and drives {@code CommandManager}/{@code ListenerManager}
 * {@code registerAll()} the way {@link Business#onEnable()} does — so missing bindings,
 * route conflicts and command-tree errors surface here instead of only at server boot
 * (business/testing/0002).
 *
 * <p>The plugin is <em>loaded</em> (data folder, config.yml/messages.properties, Paper
 * lifecycle) but not <em>enabled</em>, so its production {@code onEnable} (which fetches
 * the Treasury APIs and builds a MariaDB pool) never runs. The Treasury APIs are supplied
 * as mocks, and the real {@link DatabaseModule}'s {@code @Provides DataSource} — which
 * would open a live MariaDB pool — is overridden with a non-connecting mock, since
 * {@code registerAll()} builds MyBatis mapper proxies without touching the DB.</p>
 */
class BusinessStartupTest {

    private ServerMock server;
    private Business plugin;

    @BeforeEach
    void boot() {
        server = MockBukkit.mock();
        // Load without enabling, so the real onEnable (Treasury-API lookup + MariaDB pool)
        // never runs.
        plugin = (Business) server.getPluginManager().loadPlugin(Business.class);
    }

    @AfterEach
    void shutdown() {
        MockBukkit.unmock();
    }

    @Test
    void realInjectorRegistersAllCommandsAndListeners() {
        // The exact HiberniaModule wiring Business.onEnable() builds.
        HiberniaModule hibernia = HiberniaModule.forPlugin(plugin)
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
                        ChatCommands.class)
                .resolvers(
                        FirmPlayerResolver.class,
                        FirmNameResolver.class,
                        OnlineFirmNameResolver.class)
                .listeners(
                        FirmBalanceTaxListener.class,
                        ChestShopSaleListener.class)
                .build();

        DatabaseConfiguration dbCfg = hibernia.configuration(DatabaseConfiguration.class);

        TreasuryApi treasuryApi = mock(TreasuryApi.class);
        SalesQueryApi salesQueryApi = mock(SalesQueryApi.class);
        DataSource dataSource = mock(DataSource.class);

        // Real DatabaseModule, but its @Provides DataSource (a live MariaDB pool) swapped
        // for a non-connecting mock — registerAll never opens a connection.
        Module database = Modules.override(new DatabaseModule(dbCfg)).with(new AbstractModule() {
            @Override
            protected void configure() {
                bind(DataSource.class).toInstance(dataSource);
            }
        });

        Injector injector = Guice.createInjector(
                hibernia,
                new BusinessModule(plugin, treasuryApi, salesQueryApi),
                database);

        HiberniaStartupAssertion.assertRegisters(injector);
    }
}
