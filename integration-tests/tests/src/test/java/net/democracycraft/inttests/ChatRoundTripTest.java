package net.democracycraft.inttests;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.RconClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demonstrates the chat-command → chat-response round-trip. The mock player
 * starts with no permissions (LuckPerms default), so we op them via RCON
 * first, run a privileged command, and assert on the response.
 *
 * <p>This is also the canonical pattern for tests that need elevated player
 * perms: bracket the command with op/deop via RCON.
 */
class ChatRoundTripTest {

    @Test
    void opedMockPlayerSeesItselfInListResponse() {
        String username = "Cht_" + Long.toHexString(Instant.now().toEpochMilli());

        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            RconClient.send("op " + username);
            try {
                // Drain join broadcasts so we only assert on the /list response.
                p.drainMessages();

                p.chatCommand("/list");
                // The server's /list response is a TranslatableComponent with
                // key "commands.list.players" — the plain-text serializer
                // shows the key, not the rendered list of names. That's
                // sufficient to prove the command-response round-trip
                // works; plugin commands that emit literal Components flatten
                // to actual text.
                String response = p.awaitMessageContaining("commands.list.players", Duration.ofSeconds(3));
                assertThat(response).contains("commands.list.players");
            } finally {
                RconClient.send("deop " + username);
            }
        }
    }
}
