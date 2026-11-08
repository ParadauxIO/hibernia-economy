package io.paradaux.treasury.tasks;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.paradaux.treasury.services.AccountResolutionAuditService;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

class AccountResolutionAuditTaskTest {
    @Test
    void schedulesOffThreadAtStartupAndDaily() {
        var scheduler = mock(BukkitScheduler.class);
        var plugin = mock(Plugin.class);
        var audit = mock(AccountResolutionAuditService.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTaskTimerAsynchronously(eq(plugin), any(Runnable.class), eq(1L), eq(1728000L)))
                    .thenReturn(mock(BukkitTask.class));
            new AccountResolutionAuditTask(audit).schedule(plugin);
            verify(scheduler).runTaskTimerAsynchronously(eq(plugin), any(Runnable.class), eq(1L), eq(1728000L));
        }
    }

    @Test
    void failedQueryDoesNotCancelFutureAudits() {
        var audit = mock(AccountResolutionAuditService.class);
        when(audit.findCollisions())
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(List.of());
        var task = new AccountResolutionAuditTask(audit);
        assertThatCode(task::run).doesNotThrowAnyException();
        assertThatCode(task::run).doesNotThrowAnyException();
        verify(audit, times(2)).findCollisions();
    }
}
