package io.paradaux.hibernia.testsupport;

import io.papermc.paper.plugin.lifecycle.event.LifecycleEventOwner;
import io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;
import org.mockbukkit.mockbukkit.command.brigadier.PaperCommandsMock;
import org.mockbukkit.mockbukkit.plugin.lifecycle.event.LifecycleEventRunnerMock;

import java.lang.reflect.Field;

/**
 * Fires the Paper {@code COMMANDS} lifecycle event against the running MockBukkit
 * server. The framework's {@code CommandManager.registerAll()} only <em>registers a
 * handler</em> for that event; the Brigadier command tree — where duplicate/invalid
 * routes are rejected — is built when the event actually fires, which on a real server
 * happens during boot. This drives that same event so those failures surface in-test.
 *
 * <p>Uses MockBukkit's public {@link LifecycleEventRunnerMock} registrar API — the same
 * path MockBukkit itself uses when it loads a plugin — so no reflection into internals.</p>
 */
final class StartupLifecycle {

    private StartupLifecycle() {
    }

    /**
     * Fire the {@code COMMANDS} lifecycle event so every registered command handler
     * builds its routes into the Brigadier dispatcher. A route conflict or invalid
     * route thrown while building propagates out of here.
     */
    static void fireCommandsEvent() {
        LifecycleEventRunnerMock.INSTANCE.<LifecycleEventOwner, PaperCommandsMock>callReloadableRegistrarEvent(
                LifecycleEvents.COMMANDS,
                PaperCommandsMock.INSTANCE,
                LifecycleEventOwner.class,
                ReloadableRegistrarEvent.Cause.RELOAD);
    }

    /**
     * Flip a MockBukkit-loaded plugin's {@code enabled} flag on <em>without</em> running
     * {@code onEnable()}. Bukkit rejects listener registration for a disabled plugin
     * ({@code IllegalPluginAccessException}), but {@code JavaPlugin.setEnabled(true)}
     * would call the real {@code onEnable()} (which builds a live DB pool). The startup
     * test loads the plugin without enabling it, so we set the private flag directly —
     * enough for {@code ListenerManager.registerAll()} to register listeners as it does
     * at boot, without the DB-connecting enable body.
     */
    static void markEnabled(JavaPlugin plugin) {
        try {
            Field enabled = JavaPlugin.class.getDeclaredField("isEnabled");
            enabled.setAccessible(true);
            enabled.setBoolean(plugin, true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Could not mark the plugin enabled for the startup test (JavaPlugin.isEnabled): "
                            + e.getMessage(), e);
        }
    }
}
