package io.paradaux.chestshop.utils;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.block.SignChangeEvent;

/**
 * Reads/writes shop-sign lines through Paper's Adventure {@code Side}/{@code line} API, replacing
 * the deprecated {@code Sign.getLine}/{@code setLine} and {@code SignChangeEvent.getLine}/{@code
 * setLine}. Uses the legacy-section serializer so the {@code §}-code semantics of the old String
 * API are preserved exactly (a shop sign's colour/format codes round-trip unchanged).
 */
public final class SignText {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private SignText() {
    }

    /** The front-side text of {@code line} on a placed sign (legacy {@code §}-coded String). */
    public static String getLine(Sign sign, int line) {
        return LEGACY.serialize(sign.getSide(Side.FRONT).line(line));
    }

    /** Set the front-side text of {@code line} on a placed sign from a legacy {@code §}-coded String. */
    public static void setLine(Sign sign, int line, String text) {
        sign.getSide(Side.FRONT).line(line, LEGACY.deserialize(text == null ? "" : text));
    }

    /** The text of {@code line} on a {@link SignChangeEvent} (legacy {@code §}-coded String). */
    public static String getLine(SignChangeEvent event, int line) {
        return event.line(line) == null ? "" : LEGACY.serialize(event.line(line));
    }

    /** Set the text of {@code line} on a {@link SignChangeEvent} from a legacy {@code §}-coded String. */
    public static void setLine(SignChangeEvent event, int line, String text) {
        event.line(line, LEGACY.deserialize(text == null ? "" : text));
    }
}
