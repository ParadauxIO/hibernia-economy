package io.paradaux.chestshop.services;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.paradaux.chestshop.support.ServerTest;
import io.paradaux.treasury.api.MarketApi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyPreferenceWorkflowTest extends ServerTest {
    @TempDir
    Path folder;

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000123");
    private final MarketService market = mock(MarketService.class);
    private final MarketApi api = mock(MarketApi.class);
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final CommandSender sender = mock(CommandSender.class);

    private LegacyPreferenceImportService importer() throws Exception {
        Server bukkit = mock(Server.class);
        when(plugin.getServer()).thenReturn(bukkit);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(bukkit.getScheduler()).thenReturn(scheduler);
        when(market.enabled()).thenReturn(true);
        when(market.market()).thenReturn(api);
        doAnswer(call -> {
                    ((Runnable) call.getArgument(1)).run();
                    return null;
                })
                .when(scheduler)
                .runTaskAsynchronously(eq(plugin), any(Runnable.class));
        when(scheduler.callSyncMethod(eq(plugin), any())).thenAnswer(call -> {
            var future = new CompletableFuture<>();
            try {
                future.complete(((java.util.concurrent.Callable<?>) call.getArgument(1)).call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
            return future;
        });
        return new LegacyPreferenceImportService(plugin, folder.toFile(), market);
    }

    @Test
    void streamsExportThroughPublicApiAndReportsSuccess() throws Exception {
        Files.writeString(folder.resolve("legacy-preferences.jsonl"), row() + "\n");
        var importer = importer();
        when(plugin.isEnabled()).thenReturn(true);
        doAnswer(call -> {
                    ((Runnable) call.getArgument(1)).run();
                    return null;
                })
                .when(scheduler)
                .runTask(eq(plugin), any(Runnable.class));
        importer.importPreferences(sender);
        verify(api).importPreviewPreference(PLAYER, false);
        verify(sender).sendMessage(contains("1 accepted"));
    }

    @Test
    void malformedRowStopsBeforeLaterRowsAndCanBeRerun() throws Exception {
        var importer = importer();
        Path file = folder.resolve("legacy-preferences.jsonl");
        Files.writeString(file, "{}\n" + row() + "\n");
        importer.importPreferences(sender);
        verifyNoInteractions(api);
        Files.writeString(file, row() + "\n");
        importer.importPreferences(sender);
        verify(api).importPreviewPreference(PLAYER, false);
    }

    @Test
    void writeFailureStopsAndDoesNotImportFollowingRows() throws Exception {
        Files.writeString(folder.resolve("legacy-preferences.jsonl"), row() + "\n" + row() + "\n");
        var importer = importer();
        doThrow(new IllegalStateException("db offline")).when(api).importPreviewPreference(PLAYER, false);
        importer.importPreferences(sender);
        verify(api, times(1)).importPreviewPreference(PLAYER, false);
    }

    @Test
    void missingMarketOrConcurrentRunDoesNotQueueWork() throws Exception {
        var importer = importer();
        when(market.enabled()).thenReturn(false);
        importer.importPreferences(sender);
        verify(scheduler, never()).runTaskAsynchronously(any(), any(Runnable.class));
        when(market.enabled()).thenReturn(true);
        doReturn(null).when(scheduler).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        importer.importPreferences(sender);
        importer.importPreferences(sender);
        verify(scheduler, times(1)).runTaskAsynchronously(eq(plugin), any(Runnable.class));
        verify(sender).sendMessage(contains("already running"));
    }

    @Test
    void schedulingFailureReleasesImportLock() throws Exception {
        var importer = importer();
        doThrow(new IllegalStateException("stopped"))
                .when(scheduler)
                .runTaskAsynchronously(eq(plugin), any(Runnable.class));
        assertThatThrownBy(() -> importer.importPreferences(sender)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> importer.importPreferences(sender)).isInstanceOf(IllegalStateException.class);
        verify(scheduler, times(2)).runTaskAsynchronously(eq(plugin), any(Runnable.class));
    }

    private static String row() {
        return "{\"player_uuid\":\"" + PLAYER + "\",\"visible\":false}";
    }
}
