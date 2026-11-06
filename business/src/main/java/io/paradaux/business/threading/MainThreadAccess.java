package io.paradaux.business.threading;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.Business;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.bukkit.Bukkit;

/** Small Bukkit reads only: never submit database work or wait for a worker here. */
@Singleton
public class MainThreadAccess {
    private final Business plugin;

    @Inject
    public MainThreadAccess(Business plugin) {
        this.plugin = plugin;
    }

    public <T> T read(Supplier<T> read) {
        if (Bukkit.isPrimaryThread()) return read.get();
        var future = Bukkit.getScheduler().callSyncMethod(plugin, read::get);
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading Bukkit state", e);
        } catch (TimeoutException e) {
            future.cancel(false);
            throw new IllegalStateException("Timed out waiting for Bukkit state", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not read Bukkit state", e.getCause());
        }
    }

    public void execute(Runnable action) {
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }
}
