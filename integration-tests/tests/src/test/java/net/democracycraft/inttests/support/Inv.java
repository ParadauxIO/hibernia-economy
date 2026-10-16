package net.democracycraft.inttests.support;

import io.paradaux.mockclient.BlockPos;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RCON-driven inventory + container inspection helpers, backed by DcDiag's
 * {@code /diag invcount}, {@code /diag chestcount}, and friends. Tests use
 * these to verify item movement (the ledger only proves money moved; this
 * proves items moved).
 *
 * <p>Each {@code count} command returns a single line {@code "count=N"} that
 * the helper greps. Material names follow Bukkit's {@code Material} enum
 * (uppercase, underscored), e.g. {@code "DIRT"}, {@code "OAK_LOG"}.
 */
public final class Inv {

    private static final Pattern COUNT = Pattern.compile("count=(-?\\d+)");

    /** Item count of {@code material} in the player's inventory (main + hotbar). */
    public static int playerCount(String player, String material) {
        return parseCount(RconClient.send("diag invcount " + player + " " + material));
    }

    /** Item count of {@code material} inside the chest at {@code pos} (world: "world"). */
    public static int chestCount(BlockPos pos, String material) {
        return parseCount(RconClient.send(String.format("diag chestcount world %d %d %d %s",
                pos.x(), pos.y(), pos.z(), material)));
    }

    /** Give a player {@code n} of {@code material}, summed into their inventory. */
    public static void give(String player, String material, int n) {
        RconClient.send("diag invgive " + player + " " + material + " " + n);
    }

    /** Empty a player's inventory. */
    public static void clear(String player) {
        RconClient.send("diag invclear " + player);
    }

    /** Fill every main-inventory slot with cobblestone so subsequent buys hit NOT_ENOUGH_SPACE. */
    public static void fill(String player) {
        RconClient.send("diag invfill " + player);
    }

    /** Wait until {@link #chestCount(BlockPos, String)} reaches {@code expected} or time out. */
    public static void waitForChestCount(BlockPos pos, String material, int expected, Duration timeout) {
        Await.untilEquals(material + " in the chest at " + pos, () -> chestCount(pos, material), expected, timeout);
    }

    /** Wait until {@link #playerCount(String, String)} reaches {@code expected}. */
    public static void waitForPlayerCount(String player, String material, int expected, Duration timeout) {
        Await.untilEquals(material + " held by " + player, () -> playerCount(player, material), expected, timeout);
    }

    private static int parseCount(String rconReply) {
        Matcher m = COUNT.matcher(rconReply);
        if (!m.find()) {
            throw new IllegalStateException("expected 'count=N' in RCON reply, got: " + rconReply);
        }
        return Integer.parseInt(m.group(1));
    }

    private Inv() {}
}
