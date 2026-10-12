package net.democracycraft.inttests.support;

/**
 * Brackets an action with admin-grade permissions for the named player.
 *
 * <p>Three layers of grants:
 * <ul>
 *   <li>{@code op} — covers all {@code default: op} permissions across
 *       Treasury, Business, and the rest of the workspace's plugins.</li>
 *   <li>{@code lp user X permission set treasury.gov.* true} — Treasury
 *       declares {@code treasury.gov.*} as a wildcard permission with
 *       children. Op alone doesn't suffice because those children declare
 *       {@code default: false}.</li>
 *   <li>{@code lp user X permission set business.* true} — Business does
 *       not declare individual permissions in its plugin.yml at all. The
 *       literal {@code business.*} grant — which LuckPerms resolves
 *       broadly — keeps non-trivial Business commands dispatchable from
 *       inside tests.</li>
 * </ul>
 *
 * <pre>{@code
 * Ops.run(player.getUsername(), () -> {
 *     player.chatCommand("/business create Acme");
 *     ... assert via BusinessLedger ...
 * });
 * }</pre>
 *
 * <p>Cleanup happens in a finally block — the player is deop'd and the
 * permission grants unset even if the action throws.
 */
public final class Ops {

    public static void run(String username, Runnable action) {
        RconClient.send("op " + username);
        RconClient.send("lp user " + username + " permission set treasury.gov.* true");
        RconClient.send("lp user " + username + " permission set business.* true");
        // LuckPerms applies grants asynchronously to Bukkit's per-player
        // permission graph; Brigadier's requires(...) check runs at dispatch
        // time and reads the cached value. Without a small wait, commands
        // gated via Hibernia's @Permission annotation can be dispatched
        // before the cache is populated and silently fall through to
        // "unknown command".
        try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        try {
            action.run();
        } finally {
            RconClient.send("lp user " + username + " permission unset business.*");
            RconClient.send("lp user " + username + " permission unset treasury.gov.*");
            RconClient.send("deop " + username);
        }
    }

    private Ops() {}
}
