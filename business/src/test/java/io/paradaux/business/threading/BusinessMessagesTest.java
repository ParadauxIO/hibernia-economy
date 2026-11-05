package io.paradaux.business.threading;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.paradaux.business.Business;
import io.paradaux.hibernia.framework.i18n.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

class BusinessMessagesTest {
    @Test
    void workerDefersResolutionRenderingAndDeliveryAndSnapshotsArguments() {
        Business plugin = mock(Business.class);
        Message delegate = mock(Message.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        List<Runnable> pending = new ArrayList<>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            pending.add(call.getArgument(1));
            return null;
        });
        var messages = new BusinessMessages(delegate, new MainThreadAccess(plugin));
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        var recipients = new ArrayList<>(List.of(player));
        Object[] values = {"name", "original"};
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            messages.send(uuid, "direct", values);
            messages.send(recipients, "roster", values);
            messages.broadcast("broadcast", values);
            verifyNoInteractions(delegate);
            values[1] = "changed";
            recipients.clear();
            assertThat(pending).hasSize(3);
            pending.forEach(Runnable::run);
            verify(delegate).send(uuid, "direct", "name", "original");
            verify(delegate).send(List.of(player), "roster", "name", "original");
            verify(delegate).broadcast("broadcast", "name", "original");
        }
    }

    @Test
    void serverThreadDeliversInlineButReloadStaysOnCaller() {
        Message delegate = mock(Message.class);
        var messages = new BusinessMessages(delegate, new MainThreadAccess(null));
        Player player = mock(Player.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            messages.send(player, "hello");
            verify(delegate).send(player, "hello");
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            messages.reload();
            verify(delegate).reload();
            bukkit.verify(Bukkit::getScheduler, never());
        }
    }
}
