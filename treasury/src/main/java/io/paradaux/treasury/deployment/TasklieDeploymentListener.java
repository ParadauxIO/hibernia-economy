package io.paradaux.treasury.deployment;

import io.paradaux.treasury.startup.ReleaseCohort;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** Reports activation only after the whole server has loaded, never from Treasury.onEnable alone. */
public final class TasklieDeploymentListener implements Listener {
    private final JavaPlugin plugin;
    private final TasklieDeployment deployment;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean sending = new AtomicBoolean();
    private BukkitTask task;
    private int attempts;

    TasklieDeploymentListener(JavaPlugin plugin, TasklieDeployment deployment) {
        this.plugin = plugin;
        this.deployment = deployment;
    }

    public static void register(JavaPlugin plugin) {
        if (!plugin.getConfig().getBoolean("tasklie.enabled", false)) return;
        String token = System.getenv("TASKLIE_DEPLOYMENT_TOKEN");
        if (token == null || token.isBlank()) token = plugin.getConfig().getString("tasklie.token", "");
        try {
            var deployment = new TasklieDeployment(
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(5))
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build(),
                    TasklieDeployment.ENDPOINT,
                    token,
                    plugin.getConfig().getString("tasklie.environment", "democracycraft-plugins"),
                    plugin.getConfig().getString("tasklie.app", "democracycraft-plugins"),
                    plugin.getDescription().getVersion(),
                    Instant.now());
            plugin.getServer()
                    .getPluginManager()
                    .registerEvents(new TasklieDeploymentListener(plugin, deployment), plugin);
        } catch (IllegalArgumentException failure) {
            // Never print configuration values, tokens, request/response bodies or exception details.
            plugin.getLogger()
                    .warning(
                            "Tasklie deployment reporting disabled: check token, environment, app and release version.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        if (task != null) return;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1200L);
    }

    private void tick() {
        if (finished.get()) {
            task.cancel();
            return;
        }
        // Inspect Bukkit state on the server thread before every attempt, including retries.
        try {
            ReleaseCohort.verify(plugin.getDescription().getVersion(), name -> {
                var member = plugin.getServer().getPluginManager().getPlugin(name);
                return member != null && member.isEnabled()
                        ? member.getDescription().getVersion()
                        : null;
            });
        } catch (IllegalStateException incomplete) {
            task.cancel();
            plugin.getLogger()
                    .warning("Tasklie deployment not reported: economy plugins are disabled or versions differ.");
            return;
        }
        if (!sending.compareAndSet(false, true)) return;
        attempts++;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var result = deployment.send();
                if (result == TasklieDeployment.Result.ACCEPTED) {
                    finished.set(true);
                    plugin.getLogger()
                            .info("Tasklie confirmed live economy release "
                                    + plugin.getDescription().getVersion());
                } else if (result == TasklieDeployment.Result.REJECTED || attempts >= 10) {
                    finished.set(true);
                    plugin.getLogger()
                            .warning(
                                    "Tasklie deployment report failed; check deployment credentials and connectivity. No further retries this startup.");
                } else {
                    plugin.getLogger().warning("Tasklie deployment report unavailable; retrying in 60 seconds.");
                }
            } finally {
                sending.set(false);
            }
        });
    }
}
