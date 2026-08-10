package io.paradaux.hibernia.testsupport;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import io.paradaux.hibernia.framework.commander.CommandManager;
import io.paradaux.hibernia.framework.events.ListenerManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Assertions;

import java.util.List;

/**
 * Startup-shaped assertion shared by the Paper plugins: build (or accept) the real
 * Guice injector and drive the framework's {@link CommandManager} and
 * {@link ListenerManager} the way {@code onEnable()} does, so the failures that
 * otherwise only appear at server boot surface in a unit test instead.
 *
 * <p>What it catches:</p>
 * <ul>
 *   <li><b>Missing / mis-wired Guice bindings.</b> Resolving {@code CommandManager}
 *       and {@code ListenerManager} from the injector forces Guice to construct every
 *       bound {@code CommandHandler}, {@code ParameterResolver} and {@code Listener}
 *       (they are constructor-injected into the two managers' multibinder sets), so a
 *       command/listener whose transitive dependency has no binding throws here.</li>
 *   <li><b>Route conflicts and invalid routes.</b> {@code CommandManager.registerAll()}
 *       registers a Paper {@code COMMANDS} lifecycle handler that builds the Brigadier
 *       tree lazily; this assertion then fires that lifecycle event (via the running
 *       MockBukkit server) so the tree is actually built and duplicate/invalid routes
 *       surface — exactly as they would on a real boot.</li>
 * </ul>
 *
 * <p>The class is framework-typed (Guice + hibernia-framework), never plugin-specific:
 * a consumer builds its own module list (its real {@code HiberniaModule} + plugin
 * modules, with a non-connecting {@link javax.sql.DataSource} stand-in for the DB —
 * {@code registerAll} never touches the DB) and hands it here.</p>
 */
public final class HiberniaStartupAssertion {

    private HiberniaStartupAssertion() {
    }

    /**
     * Build an injector from {@code modules} and assert it registers cleanly.
     *
     * @param modules the plugin's real module graph (its {@code HiberniaModule} plus
     *                the plugin/database modules), wired over a running MockBukkit
     *                server with a non-connecting DataSource where a DB is needed.
     * @return the built injector, so a caller can make further assertions on it.
     */
    public static Injector assertRegisters(List<? extends Module> modules) {
        Injector injector;
        try {
            injector = Guice.createInjector(modules);
        } catch (RuntimeException e) {
            throw new AssertionError(
                    "Building the Guice injector failed — a module or binding is broken at startup: "
                            + rootMessage(e), e);
        }
        return assertRegisters(injector);
    }

    /**
     * Assert that an already-built injector registers its commands and listeners
     * without error.
     *
     * <p>Resolving the two managers proves every command handler, resolver and
     * listener is constructible (bindings present); {@code registerAll()} plus the
     * forced {@code COMMANDS} lifecycle pass proves the routes bind without conflict.</p>
     *
     * @param injector the plugin's real injector, built over a MockBukkit server.
     * @return the same injector, for chaining further assertions.
     */
    public static Injector assertRegisters(Injector injector) {
        CommandManager commandManager;
        ListenerManager listenerManager;

        // (1) Resolving the managers forces Guice to construct every bound
        //     CommandHandler / ParameterResolver / Listener — a missing binding on any
        //     command or listener (or its transitive deps) throws right here.
        try {
            commandManager = injector.getInstance(CommandManager.class);
            listenerManager = injector.getInstance(ListenerManager.class);
        } catch (RuntimeException e) {
            throw new AssertionError(
                    "Resolving CommandManager/ListenerManager failed — a command, resolver or "
                            + "listener has a missing or mis-wired Guice binding: " + rootMessage(e), e);
        }

        // (2) registerAll() hooks the Paper COMMANDS lifecycle (commands) and registers
        //     listeners with the plugin manager (listeners). Neither touches the DB.
        //     Bukkit rejects listener registration for a not-yet-enabled plugin, so mark
        //     the (loaded-but-not-enabled) plugin enabled first — without running its
        //     DB-connecting onEnable — mirroring the boot state at registration time.
        try {
            commandManager.registerAll();
            StartupLifecycle.markEnabled(injector.getInstance(JavaPlugin.class));
            listenerManager.registerAll();
        } catch (RuntimeException e) {
            throw new AssertionError(
                    "registerAll() threw — command/listener registration is broken at startup: "
                            + rootMessage(e), e);
        }

        // (3) The command tree is built lazily inside the COMMANDS lifecycle handler, so
        //     route conflicts / invalid routes only surface when that event fires. Drive
        //     it now (via the MockBukkit server) so they surface in-test, not at boot.
        try {
            StartupLifecycle.fireCommandsEvent();
        } catch (RuntimeException e) {
            throw new AssertionError(
                    "Firing the COMMANDS lifecycle event failed — a route conflict or invalid "
                            + "route was found while building the command tree: " + rootMessage(e), e);
        }

        Assertions.assertNotNull(commandManager, "CommandManager resolved");
        Assertions.assertNotNull(listenerManager, "ListenerManager resolved");
        return injector;
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return (msg == null ? root.getClass().getName() : msg);
    }
}
