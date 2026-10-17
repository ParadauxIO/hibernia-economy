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
 * <p>LuckPerms applies grants on its own thread after the RCON command returns, and a
 * command's permission gate reads the player's permissions at dispatch time — a command
 * sent too early is silently treated as unknown. So both directions wait for the change to
 * be visible ({@link Perms}): the action runs only once the grants are live, and cleanup
 * returns only once they are gone, so a later denial assertion on the same player is sound.
 *
 * <p>Cleanup happens in a finally block — the player is deop'd and the
 * permission grants unset even if the action throws.
 */
public final class Ops {

    public static void run(String username, Runnable action) {
        RconClient.send("op " + username);
        Perms.grant(username, "treasury.gov.*");
        Perms.grant(username, "business.*");
        try {
            action.run();
        } finally {
            Perms.revoke(username, "business.*");
            Perms.revoke(username, "treasury.gov.*");
            RconClient.send("deop " + username);
        }
    }

    private Ops() {}
}
