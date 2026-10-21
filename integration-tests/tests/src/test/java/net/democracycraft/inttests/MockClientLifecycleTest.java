package net.democracycraft.inttests;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class MockClientLifecycleTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void waitingForLaterReplyPreservesEarlierReplyArrivingDuringWait() throws Exception {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Buf"))) {
            Barrier.settle();
            player.drainMessages();
            CountDownLatch observedFirst = new CountDownLatch(1);
            var second = CompletableFuture.supplyAsync(() -> player.awaitMessage(line -> {
                if (line.contains("dcsync first")) observedFirst.countDown();
                return line.contains("dcsync second");
            }, TIMEOUT));
            player.chatCommand("/dcsync first");
            assertThat(observedFirst.await(5, TimeUnit.SECONDS)).isTrue();
            player.chatCommand("/dcsync second");
            assertThat(second.get(5, TimeUnit.SECONDS)).contains("dcsync second");
            assertThat(player.awaitMessageContaining("dcsync first", TIMEOUT)).contains("dcsync first");
        }
    }

    @Test
    void rejectedLoginFailsAtConnectWithServerReasonAndNoPhantomPlayer() {
        String name = Usernames.fresh("Kick");
        try (MockPlayer first = MockPlayer.connectLocal(name)) {
            Barrier.settle(first);
        }
        Await.until("logout processed", () -> Perms.check(name, "treasury.pay"), "offline"::equals, TIMEOUT);
        // This fixture deliberately retains GP's default 60-second login cooldown.
        assertThatThrownBy(() -> {
            try (MockPlayer unexpected = MockPlayer.connectLocal(name)) {
                fail("rejected login returned a MockPlayer");
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageMatching("Login failed for " + name
                        + ": You must wait ([1-9]|[1-5][0-9]|60) seconds before logging-in again\\.");
        assertThat(MockPlayer.openPlayers()).noneMatch(player -> player.getUsername().equals(name));
    }
}
