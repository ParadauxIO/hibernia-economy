package net.democracycraft.inttests.diag;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Usernames;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDisguisedChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * Diagnostic: send /balance and observe which clientbound chat-family packet
 * (if any) the server emits. Prints results to stderr.
 */
class ChatProbeTest {

    @Test
    void probeBalanceChatPackets() throws Exception {
        String username = Usernames.fresh("Probe");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            Thread.sleep(2000);
            p.drainMessages();
            System.err.println("[probe] sending /balance as " + username);
            p.chatCommand("/balance");

            tryAwait("SystemChat",   () -> p.awaitClientbound(ClientboundSystemChatPacket.class,   pkt -> true, Duration.ofSeconds(2)));
            tryAwait("PlayerChat",   () -> p.awaitClientbound(ClientboundPlayerChatPacket.class,   pkt -> true, Duration.ofSeconds(2)));
            tryAwait("DisguisedChat",() -> p.awaitClientbound(ClientboundDisguisedChatPacket.class,pkt -> true, Duration.ofSeconds(2)));

            try {
                String first = p.awaitMessage(m -> true, Duration.ofMillis(50));
                System.err.println("[probe] queued message=" + first);
            } catch (Throwable t) {
                System.err.println("[probe] (queue empty)");
            }
        }
    }

    private static void tryAwait(String label, java.util.concurrent.Callable<?> awaiter) {
        try {
            Object pkt = awaiter.call();
            System.err.println("[probe] received " + label + " packet: " + pkt);
        } catch (Throwable t) {
            System.err.println("[probe] NO " + label + " packet within 2s (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
        }
    }
}
