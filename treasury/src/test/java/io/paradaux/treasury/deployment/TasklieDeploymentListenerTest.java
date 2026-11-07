package io.paradaux.treasury.deployment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TasklieDeploymentListenerTest {
    private static final String VERSION = "2026.10.18.1-g41b9bbf9f528";
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final Server server = mock(Server.class);
    private final PluginManager manager = mock(PluginManager.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final BukkitTask task = mock(BukkitTask.class);
    private final TasklieDeployment deployment = mock(TasklieDeployment.class);
    private final Map<String, Plugin> members = new HashMap<>();
    private Runnable tick;
    private Runnable async;

    @BeforeEach
    void setUp() {
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile("Treasury", VERSION, "test.Main"));
        when(server.getPluginManager()).thenReturn(manager);
        when(server.getScheduler()).thenReturn(scheduler);
        for (String name : new String[] {"Treasury", "Business", "ChestShop", "TreasuryAPI"}) {
            Plugin member = mock(Plugin.class);
            when(member.isEnabled()).thenReturn(true);
            when(member.getDescription()).thenReturn(new PluginDescriptionFile(name, VERSION, "test.Main"));
            members.put(name, member);
        }
        when(manager.getPlugin(anyString())).thenAnswer(call -> members.get(call.getArgument(0)));
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1200L)))
                .thenAnswer(call -> {
                    tick = call.getArgument(1);
                    return task;
                });
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            async = call.getArgument(1);
            return mock(BukkitTask.class);
        });
    }

    @Test
    void waitsForServerLoadAndSendsOffThreadOnce() {
        var listener = new TasklieDeploymentListener(plugin, deployment);
        verifyNoInteractions(scheduler, deployment);
        listener.onServerLoad(mock(ServerLoadEvent.class));
        listener.onServerLoad(mock(ServerLoadEvent.class));
        verify(scheduler).runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1200L));
        tick.run();
        verifyNoInteractions(deployment);
        when(deployment.send()).thenReturn(TasklieDeployment.Result.ACCEPTED);
        async.run();
        tick.run();
        verify(deployment).send();
        verify(task).cancel();
    }

    @Test
    void disabledOrMixedPluginNeverReportsLive() {
        when(members.get("Business").isEnabled()).thenReturn(false);
        new TasklieDeploymentListener(plugin, deployment).onServerLoad(mock(ServerLoadEvent.class));
        tick.run();
        verifyNoInteractions(deployment);
        assertThat(async).isNull();
        when(members.get("Business").isEnabled()).thenReturn(true);
        when(members.get("ChestShop").getDescription())
                .thenReturn(new PluginDescriptionFile("ChestShop", "old", "test.Main"));
        tick.run();
        assertThat(async).isNull();
        verify(task, times(2)).cancel();
    }

    @Test
    void retriesAreBoundedAndDoNotOverlap() {
        when(deployment.send()).thenReturn(TasklieDeployment.Result.RETRY);
        new TasklieDeploymentListener(plugin, deployment).onServerLoad(mock(ServerLoadEvent.class));
        for (int attempt = 0; attempt < 10; attempt++) {
            tick.run();
            tick.run(); // Previous HTTP request is still in flight.
            async.run();
        }
        tick.run();
        verify(deployment, times(10)).send();
        verify(scheduler, times(10)).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        verify(task).cancel();
    }

    @Test
    void rechecksPluginHealthBeforeRetry() {
        when(deployment.send()).thenReturn(TasklieDeployment.Result.RETRY);
        new TasklieDeploymentListener(plugin, deployment).onServerLoad(mock(ServerLoadEvent.class));
        tick.run();
        async.run();
        members.remove("TreasuryAPI");
        tick.run();
        verify(deployment).send();
        verify(task).cancel();
    }

    @Test
    void reportingIsOptIn() {
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        TasklieDeploymentListener.register(plugin);
        verify(manager, never()).registerEvents(any(), any());
        verifyNoInteractions(scheduler, deployment);
    }
}
