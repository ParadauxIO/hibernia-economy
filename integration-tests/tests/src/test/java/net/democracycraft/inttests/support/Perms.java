package net.democracycraft.inttests.support;

import java.time.Duration;

/**
 * LuckPerms grants that are live when the call returns.
 *
 * <p>{@code lp ...} commands sent over RCON return before LuckPerms has applied them (it
 * runs commands on its own executor), so a command issued right after a grant can be
 * checked against the old permissions. Each helper here sends the {@code lp} command and
 * then polls {@code /diag perm} — {@code Player#hasPermission} on the main thread, the same
 * check a command's permission gate makes — until the change is visible.
 *
 * <p>LuckPerms executes its commands one at a time, in order, so waiting for the last
 * change of a sequence (e.g. {@code creategroup}, then {@code group … permission set},
 * then {@code user … parent add}) also covers the earlier ones.
 */
public final class Perms {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * {@code lp user <user> permission set <node> true}, then wait until the node is set on
     * the player and held. ({@code has} alone can be true early — an op holds any undeclared
     * node by default — so {@code set} is what shows LuckPerms applied the grant.)
     */
    public static void grant(String user, String node) {
        RconClient.send("lp user " + user + " permission set " + node + " true");
        Await.until(user + " to be granted " + node,
                () -> check(user, node), "has=true set=true"::equals, TIMEOUT);
    }

    /** {@code lp user <user> permission unset <node>}, then wait until it is no longer set. */
    public static void revoke(String user, String node) {
        RconClient.send("lp user " + user + " permission unset " + node);
        awaitUnset(user, node);
    }

    /** Wait until the online player has {@code node} (directly, by group or wildcard). */
    public static void awaitHas(String user, String node) {
        Await.until(user + " to have " + node,
                () -> check(user, node), r -> r.contains("has=true"), TIMEOUT);
    }

    /**
     * Wait until the grant is gone: {@code node} no longer set on the player, or no longer
     * held, or the player went offline. ({@code set} alone can stay true for a node whose
     * plugin.yml default applies to the player; {@code has} alone can stay true by default.)
     */
    public static void awaitUnset(String user, String node) {
        Await.until(user + " to no longer have " + node + " set",
                () -> check(user, node),
                r -> r.contains("set=false") || r.contains("has=false") || r.equals("offline"), TIMEOUT);
    }

    /** {@code has=<bool> set=<bool>}, or {@code offline}. */
    public static String check(String user, String node) {
        return RconClient.send("diag perm " + user + " " + node).trim();
    }

    private Perms() {}
}
