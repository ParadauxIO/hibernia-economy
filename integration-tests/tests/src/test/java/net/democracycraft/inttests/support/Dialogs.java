package net.democracycraft.inttests.support;

import io.paradaux.mockclient.MockPlayer;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundShowDialogGamePacket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Reads actual dialog wire data and submits the server-issued callback with player input. */
public final class Dialogs {
    public static NbtMap await(MockPlayer player, Runnable action, Predicate<NbtMap> matches) {
        return player.awaitClientboundAfter(action, ClientboundShowDialogGamePacket.class,
                packet -> packet.getDialog().isCustom() && matches.test(packet.getDialog().custom()),
                Duration.ofSeconds(10)).getDialog().custom();
    }

    public static List<NbtMap> buttons(NbtMap dialog) {
        return dialog.getList("actions", NbtType.COMPOUND);
    }

    public static void click(MockPlayer player, NbtMap dialog, String label, Map<String, ?> inputs) {
        NbtMap button = buttons(dialog).stream().filter(b -> text(b.get("label")).equals(label))
                .findFirst().orElseThrow(() -> new AssertionError("Missing button " + label + " in " + dialog));
        NbtMap action = button.getCompound("action");
        if (!action.getString("type").equals("minecraft:dynamic/custom")) {
            throw new AssertionError("Unsupported dialog action " + action);
        }
        var payload = action.getCompound("additions").toBuilder();
        inputs.forEach((key, value) -> {
            if (value instanceof String text) payload.putString(key, text);
            else if (value instanceof Float number) payload.putFloat(key, number);
            else throw new IllegalArgumentException("Unsupported dialog input: " + key);
        });
        // Paper 1.21.11 enqueues new callback registrations and drains them on a
        // server tick. A mock can reply before that drain; a packet echo alone
        // does not prove a tick boundary. Observe two world ticks, without retries.
        long tick = gameTime();
        Await.until("Paper callback registration tick", Dialogs::gameTime,
                now -> now - tick >= 2, Duration.ofSeconds(5));
        player.clickDialogAction(action.getString("id"), payload.build());
    }

    /** Plain text of a protocol chat component; ignores styling and click/hover metadata. */
    public static String text(Object value) {
        if (value instanceof String s) return s;
        if (value instanceof Map<?, ?> map) {
            return text(map.get("text")) + text(map.get("")) + text(map.get("extra"));
        }
        if (value instanceof List<?> list) return list.stream().map(Dialogs::text).reduce("", String::concat);
        return "";
    }

    private static long gameTime() {
        String reply = RconClient.send("time query gametime").trim();
        var match = java.util.regex.Pattern.compile("(\\d+)$").matcher(reply);
        if (!match.find()) throw new IllegalStateException("Cannot read server tick: " + reply);
        return Long.parseLong(match.group(1));
    }

    public static String title(NbtMap dialog) { return text(dialog.get("title")); }
    private Dialogs() {}
}
