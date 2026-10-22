package net.democracycraft.inttests.support;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.paradaux.mockclient.MockPlayer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import static org.assertj.core.api.Assertions.assertThat;

/** Real menu actions; DcDiag only observes the server's resulting inventory. */
public final class Menus {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private Menus() {}

    public static ClientboundOpenScreenPacket open(MockPlayer player, Runnable action) {
        var screen = player.awaitClientboundAfter(action, ClientboundOpenScreenPacket.class, p -> true, TIMEOUT);
        Await.until("menu contents", () -> player.containerContent(screen.getContainerId()).orElse(null), p -> p != null, TIMEOUT);
        Barrier.sync(player);
        return screen;
    }

    public static ClientboundOpenScreenPacket click(MockPlayer player, ClientboundOpenScreenPacket screen,
            int slot, ClickItemAction action) {
        var content = player.containerContent(screen.getContainerId()).orElseThrow();
        return open(player, () -> player.sendPacket(new ServerboundContainerClickPacket(screen.getContainerId(),
                content.getStateId(), slot, ContainerActionType.CLICK_ITEM, action, null, Map.of())));
    }

    public static JsonObject state(MockPlayer player) { return json("menustate " + player.getUsername()); }
    public static JsonObject slot(MockPlayer player, int slot) { return json("menuslot " + player.getUsername() + " " + slot); }
    public static String summary(MockPlayer player) { return slot(player, 49).get("lore").getAsString(); }
    public static List<String> offers(MockPlayer player) {
        return IntStream.range(0, 45).mapToObj(i -> slot(player, i).toString())
                .filter(s -> s.contains("Buy")).toList();
    }
    private static JsonObject json(String command) {
        String reply = RconClient.send("diag " + command).trim();
        assertThat(reply).as(command).startsWith("{");
        return JsonParser.parseString(reply).getAsJsonObject();
    }
}
