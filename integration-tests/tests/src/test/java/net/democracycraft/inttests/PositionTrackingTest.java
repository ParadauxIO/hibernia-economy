package net.democracycraft.inttests;

import io.paradaux.mockclient.MockPlayer;
import io.paradaux.mockclient.Position;
import net.democracycraft.inttests.support.RconClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates position tracking: after the play-state Login packet, Paper
 * sends a ClientboundPlayerPositionPacket immediately. {@link MockPlayer}
 * captures it and exposes the current position via {@link MockPlayer#getPosition()}.
 *
 * <p>Then we use {@link RconClient} to teleport the player and verify that
 * {@link MockPlayer#awaitPositionNear} sees the new location.
 */
class PositionTrackingTest {

    @Test
    void initialPositionIsPopulatedOnJoin() {
        String username = "Pos_" + Long.toHexString(Instant.now().toEpochMilli());

        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            // The first ClientboundPlayerPositionPacket should arrive within
            // a second of the play-state Login packet. Allow up to 3s for slow CI.
            Position pos = p.awaitPositionMatching(any -> true, Duration.ofSeconds(3));
            assertThat(pos).isNotNull();
            assertThat(p.getPosition()).isPresent();
        }
    }

    @Test
    void rconTeleportIsObservedOnTheClient() {
        String username = "Tp_" + Long.toHexString(Instant.now().toEpochMilli());

        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            // Wait for the initial position so we have a baseline.
            Position before = p.awaitPositionMatching(any -> true, Duration.ofSeconds(3));

            // RCON teleport to a far-away spot.
            Position target = new Position(500.5, 80.0, -500.5, 0f, 0f);
            RconClient.send("execute as " + username + " run teleport "
                    + target.x() + " " + target.y() + " " + target.z());

            Position after = p.awaitPositionNear(target, 2.0, Duration.ofSeconds(5));
            assertThat(after.distanceTo(target)).isLessThanOrEqualTo(2.0);
            assertThat(after.distanceTo(before)).isGreaterThan(100.0);
        }
    }
}
