package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.services.*;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LegacyShopImportWorkflowTest {
    @TempDir Path folder;
    private static final String ROW = """
            {"world_uuid":"01234567-89ab-cdef-0123-456789abcdef","pos_x":1,"pos_y":2,"pos_z":3,"owner_name":"Owner","item_bytes":"AQ==","buy_price":1,"sell_price":null,"quantity":1,"stock":1,"estimated_capacity":1,"visible":1,"hologram":1,"last_updated":null}
            """;

    @Test void streamsExportReportsRejectedLinesAndGuardsConcurrentRuns() throws Exception {
        Files.writeString(folder.resolve("legacy-shops.jsonl"), ROW + "invalid\n" + ROW);
        var fixture = fixture();
        doNothing().when(fixture.importer).importRow(any());
        fixture.importer.importShops(fixture.sender);
        fixture.importer.importShops(fixture.sender);
        verify(fixture.sender).sendMessage("A legacy shop import is already running.");
        fixture.run();
        verify(fixture.importer, times(2)).importRow(any());
        verify(fixture.sender).sendMessage(contains("2 accepted (including existing locations), 1 rejected"));
        fixture.importer.importShops(fixture.sender);
        verify(fixture.scheduler, times(2)).runTaskAsynchronously(eq(fixture.plugin), any(Runnable.class));
    }

    @Test void writeFailureStopsAndCanBeRetried() throws Exception {
        Files.writeString(folder.resolve("legacy-shops.jsonl"), ROW + ROW);
        var fixture = fixture();
        doThrow(new IllegalStateException("DB unavailable")).when(fixture.importer).importRow(any());
        fixture.importer.importShops(fixture.sender);
        fixture.run();
        verify(fixture.importer).importRow(any());
        verify(fixture.sender).sendMessage(contains("Stopped at line 1"));
        doNothing().when(fixture.importer).importRow(any());
        fixture.importer.importShops(fixture.sender);
        verify(fixture.scheduler, times(2)).runTaskAsynchronously(eq(fixture.plugin), any(Runnable.class));
    }

    @Test void unavailableApiDoesNotStartImport() {
        var plugin = mock(JavaPlugin.class);
        var sender = mock(CommandSender.class);
        var importer = new LegacyShopImportServiceImpl(plugin, folder.toFile(), mock(MarketService.class),
                mock(AccountService.class), mock(SignService.class));
        importer.importShops(sender);
        verify(sender).sendMessage("Legacy import requires Treasury MarketApi.");
        verifyNoInteractions(plugin);
    }

    @Test void schedulerRejectionReleasesRunningGuard() {
        var fixture = fixture();
        when(fixture.scheduler.runTaskAsynchronously(eq(fixture.plugin), any(Runnable.class)))
                .thenThrow(new IllegalStateException("disabled"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> fixture.importer.importShops(fixture.sender));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> fixture.importer.importShops(fixture.sender));
        verify(fixture.scheduler, times(2)).runTaskAsynchronously(eq(fixture.plugin), any(Runnable.class));
    }

    @Test void shutdownInterruptCancelsQueuedWriteAndDoesNotScheduleMessages() throws Exception {
        Files.writeString(folder.resolve("legacy-shops.jsonl"), ROW);
        var fixture = fixture();
        var task = mock(java.util.concurrent.Future.class);
        when(task.get(anyLong(), any())).thenThrow(new InterruptedException());
        when(fixture.scheduler.callSyncMethod(eq(fixture.plugin), any())).thenReturn(task);
        when(fixture.plugin.isEnabled()).thenReturn(false);
        fixture.importer.importShops(fixture.sender);
        try {
            fixture.run();
            org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        verify(task).cancel(false);
        verify(fixture.scheduler, never()).runTask(eq(fixture.plugin), any(Runnable.class));
        verify(fixture.importer, never()).importRow(any());
    }

    private Fixture fixture() {
        var plugin = mock(JavaPlugin.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        var market = mock(MarketService.class);
        var sender = mock(CommandSender.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.isEnabled()).thenReturn(true);
        when(server.getScheduler()).thenReturn(scheduler);
        when(market.enabled()).thenReturn(true);
        when(scheduler.callSyncMethod(eq(plugin), any())).thenAnswer(invocation -> {
            Callable<Object> callable = invocation.getArgument(1);
            var task = new FutureTask<>(callable);
            task.run();
            return task;
        });
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        });
        var importer = spy(new LegacyShopImportServiceImpl(plugin, folder.toFile(), market,
                mock(AccountService.class), mock(SignService.class)));
        return new Fixture(importer, plugin, scheduler, sender);
    }

    private record Fixture(LegacyShopImportServiceImpl importer, JavaPlugin plugin,
                           BukkitScheduler scheduler, CommandSender sender) {
        void run() {
            var task = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runTaskAsynchronously(eq(plugin), task.capture());
            task.getValue().run();
        }
    }
}
