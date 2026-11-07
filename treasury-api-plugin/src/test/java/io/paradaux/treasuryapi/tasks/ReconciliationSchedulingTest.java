package io.paradaux.treasuryapi.tasks;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import io.paradaux.treasuryapi.TreasuryAPI;
import io.paradaux.treasuryapi.mappers.ExplorerGroupMapper;
import io.paradaux.treasuryapi.services.DerivedGroupReconciliation;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.luckperms.api.LuckPerms;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class ReconciliationSchedulingTest {
    @Test
    void disableCancelsScheduledWorkEvenWhenStartupDidNotCreateInjector() {
        TreasuryAPI plugin = mock(TreasuryAPI.class);
        Server server = mock(Server.class);
        AsyncScheduler clock = mock(AsyncScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getAsyncScheduler()).thenReturn(clock);
        when(server.getServicesManager()).thenReturn(mock(ServicesManager.class));
        doCallRealMethod().when(plugin).onDisable();

        plugin.onDisable();

        verify(clock).cancelTasks(plugin);
    }

    @ParameterizedTest
    @CsvSource({"1800,1800", "7200,1800", "60,60", "0,1", "-1,1"})
    void bothSourcesRefreshOnRealSecondsWithoutServerTicks(long configured, long expected) {
        TreasuryAPI plugin = mock(TreasuryAPI.class);
        Server server = mock(Server.class);
        AsyncScheduler clock = mock(AsyncScheduler.class);
        BukkitScheduler ticks = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("schedule-test"));
        when(server.getAsyncScheduler()).thenReturn(clock);
        when(ticks.runTaskTimerAsynchronously(eq(plugin), any(Runnable.class), anyLong(), anyLong()))
                .thenReturn(mock(BukkitTask.class));
        ExplorerGroupMapper mapper = mock(ExplorerGroupMapper.class);
        DerivedGroupReconciliation derived = mock(DerivedGroupReconciliation.class);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(ticks);
            new GroupReconciliationTask(plugin, mapper, mock(LuckPerms.class)).schedule(configured);
            new DerivedGroupReconciliationTask(plugin, derived).schedule(configured);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Consumer<ScheduledTask>> callbacks = ArgumentCaptor.forClass(Consumer.class);
            verify(clock, times(2))
                    .runAtFixedRate(eq(plugin), callbacks.capture(), eq(1L), eq(expected), eq(TimeUnit.SECONDS));
            verifyNoInteractions(ticks);
            // Invoke the real scheduled work without advancing any Minecraft ticks.
            callbacks.getAllValues().forEach(callback -> callback.accept(mock(ScheduledTask.class)));
            verify(mapper).listSyncedGroups();
            verify(derived).reconcile();
        }
    }
}
