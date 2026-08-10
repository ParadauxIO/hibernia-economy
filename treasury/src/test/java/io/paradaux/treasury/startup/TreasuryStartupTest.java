package io.paradaux.treasury.startup;

import com.google.inject.Module;
import io.paradaux.hibernia.framework.guice.HiberniaModule;
import io.paradaux.hibernia.testsupport.HiberniaStartupAssertion;
import io.paradaux.treasury.Treasury;
import io.paradaux.treasury.commands.BalanceCommand;
import io.paradaux.treasury.commands.BaltopCommand;
import io.paradaux.treasury.commands.EcoCommand;
import io.paradaux.treasury.commands.EconomyCommand;
import io.paradaux.treasury.commands.FineCommand;
import io.paradaux.treasury.commands.GovCommand;
import io.paradaux.treasury.commands.PayAccountCommand;
import io.paradaux.treasury.commands.PayCommand;
import io.paradaux.treasury.commands.SalesCommand;
import io.paradaux.treasury.commands.TaxCommand;
import io.paradaux.treasury.commands.TransactionsCommand;
import io.paradaux.treasury.commands.TreasuryCommand;
import io.paradaux.treasury.commands.resolvers.PayTargetResolver;
import io.paradaux.treasury.events.FirstPlayerJoinEvent;
import io.paradaux.treasury.events.PlayerLoginListener;
import io.paradaux.treasury.guice.DatabaseModule;
import io.paradaux.treasury.guice.TreasuryModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import javax.sql.DataSource;
import java.util.List;

import static org.mockito.Mockito.mock;

/**
 * Startup-shaped test: builds Treasury's <em>real</em> Guice module graph over a live
 * MockBukkit server and drives {@code CommandManager}/{@code ListenerManager}
 * {@code registerAll()} the way {@link Treasury#onEnable()} does — so missing bindings,
 * route conflicts and command-tree errors surface here instead of only at server boot
 * (treasury/testing/0001).
 *
 * <p>The plugin is <em>loaded</em> (so it has a data folder, config.yml/messages.properties
 * and the Paper lifecycle manager) but not <em>enabled</em>, so its production
 * {@code onEnable} — which builds a real MariaDB Hikari pool — never runs. The module list
 * mirrors {@code onEnable} exactly, except the DataSource is a non-connecting mock:
 * {@code registerAll()} constructs mapper proxies without opening a connection, so no DB
 * is needed for a wiring test.</p>
 */
class TreasuryStartupTest {

    private ServerMock server;
    private Treasury plugin;

    @BeforeEach
    void boot() {
        server = MockBukkit.mock();
        // Load (construct + set data folder / description / lifecycle) WITHOUT enabling,
        // so the real onEnable (which builds a live MariaDB pool) never runs.
        plugin = (Treasury) server.getPluginManager().loadPlugin(Treasury.class);
    }

    @AfterEach
    void shutdown() {
        MockBukkit.unmock();
    }

    @Test
    void realInjectorRegistersAllCommandsAndListeners() {
        // The exact HiberniaModule wiring Treasury.onEnable() builds.
        HiberniaModule hibernia = HiberniaModule.forPlugin(plugin)
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
                        PlayerLoginListener.class)
                .build();

        // A non-connecting DataSource: registerAll builds MyBatis mapper proxies without
        // touching the DB, so a mock stands in for the production MariaDB pool.
        DataSource dataSource = mock(DataSource.class);

        List<Module> modules = List.of(
                hibernia,
                new TreasuryModule(plugin),
                new DatabaseModule(dataSource));

        // Resolves both managers (every command/resolver/listener binding), registers
        // them, and fires the COMMANDS lifecycle so route conflicts surface.
        HiberniaStartupAssertion.assertRegisters(modules);
    }
}
