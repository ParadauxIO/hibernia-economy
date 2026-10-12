package net.democracycraft.inttests;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.RconClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates concurrent mock players. Two clients connect at once, each is
 * its own offline UUID, and both are online and reachable at the same time.
 *
 * <p>This is the foundation for cross-player tests: A pays B, A creates a
 * firm and B joins it, etc.
 */
class MultiPlayerTest {

    @Test
    void twoConcurrentMockPlayersSeeEachOther() {
        long now = Instant.now().toEpochMilli();
        String alice = "A_" + Long.toHexString(now);
        String bob = "B_" + Long.toHexString(now);

        try (MockPlayer a = MockPlayer.connectLocal(alice);
             MockPlayer b = MockPlayer.connectLocal(bob)) {

            // Both are online at once, as the server sees it…
            String online = RconClient.send("list");
            assertThat(online).contains(alice).contains(bob);

            // …and Alice's connection is live while Bob's is: a message addressed to
            // Alice that names Bob through a selector only resolves if Bob is online.
            // (Not the vanilla join broadcast: GriefPrevention silences join/quit
            // notifications when they come quickly, which a test suite always does.)
            RconClient.send("tellraw " + alice + " {\"selector\":\"@a[name=" + bob + "]\"}");
            assertThat(a.awaitMessageContaining(bob, Duration.ofSeconds(5))).contains(bob);

            // Their UUIDs differ (offline UUIDs derive from username).
            assertThat(a.getUuid()).isNotEqualTo(b.getUuid());
        }
    }
}
