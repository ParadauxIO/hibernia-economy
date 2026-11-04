package io.paradaux.mockclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.junit.jupiter.api.Test;

class MockPlayerPositionTest {
    @Test
    void teleportArrivingDuringSnapshotCheckIsNotLost() throws Exception {
        // Drive the receive-side state without a socket or timing-dependent threads.
        var constructor = MockPlayer.class.getDeclaredConstructor(String.class, ClientNetworkSession.class);
        constructor.setAccessible(true);
        MockPlayer player = constructor.newInstance("PositionRace", null);
        Field position = MockPlayer.class.getDeclaredField("currentPosition");
        position.setAccessible(true);
        position.set(player, new Position(0, 64, 0, 0, 0));
        Field awaiting = MockPlayer.class.getDeclaredField("awaiting");
        awaiting.setAccessible(true);
        Position target = new Position(500.5, 80, -500.5, 0, 0);
        var packet = new ClientboundPlayerPositionPacket(1, target.x(), target.y(), target.z(), 0, 0, 0, 0, 0);
        AtomicBoolean delivered = new AtomicBoolean();

        Position result = player.awaitPositionMatching(snapshot -> {
            if (delivered.compareAndSet(false, true)) {
                try {
                    // The old code reads a snapshot before registering its waiter.
                    // Deliver exactly in that gap, just as the receive thread can.
                    position.set(player, target);
                    for (Object waiter : (List<?>) awaiting.get(player)) {
                        var complete = waiter.getClass().getDeclaredMethod("tryComplete", Packet.class);
                        complete.setAccessible(true);
                        complete.invoke(waiter, packet);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                }
            }
            return snapshot.within(target, 0.01);
        }, Duration.ofMillis(100));

        assertThat(result).isEqualTo(target);
        assertThat((List<?>) awaiting.get(player)).isEmpty();
    }
}
