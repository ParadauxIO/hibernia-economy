package net.democracycraft.inttests.diag;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.Usernames;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the protocol assumption the whole suite's chat assertions rest on: plugin replies
 * reach the client as {@code ClientboundSystemChatPacket} (the only chat packet
 * {@code MockPlayer} buffers), not as player or disguised chat.
 */
class ChatProbeTest {

    @Test
    void pluginRepliesArriveAsSystemChat() {
        String username = Usernames.fresh("Probe");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            // Let join-time output finish so the next system line is /balance's reply.
            Barrier.settle(p);
            p.drainMessages();

            ClientboundSystemChatPacket reply = p.awaitClientboundAfter(() -> p.chatCommand("/balance"),
                    ClientboundSystemChatPacket.class, pkt -> !pkt.isOverlay(), Duration.ofSeconds(5));

            assertThat(reply.getContent()).as("/balance reply content").isNotNull();
        }
    }
}
