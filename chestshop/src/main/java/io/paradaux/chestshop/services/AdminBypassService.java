package io.paradaux.chestshop.services;

import io.paradaux.chestshop.utils.Permissions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Per-player, opt-out admin bypass — and the bypass-aware permission check that rides on it.
 * Staff normally hold elevated ChestShop permissions ({@code ChestShop.admin}/{@code mod}/…)
 * that let them open and break any shop and trade without being a real customer.
 * {@code /chestshop bypass} lets a staff member voluntarily switch that off so they can
 * <em>play</em> — get charged for trades, lose access to others' shops — and flip it back on
 * later.
 *
 * <p>The whole mechanic rides on {@link #has}: it returns {@code false} for the elevated
 * ("staff") nodes ({@link Permissions#isGated}) while a player is opted out, so every
 * admin-gated path demotes them to a normal player without special-casing the call site. The
 * toggle command itself is gated by the framework's Bukkit permission check (not {@link #has}),
 * so an opted-out admin can always turn it back on.
 *
 * <p>Holds the opted-out set as in-memory service state (resets on restart, re-granting bypass
 * by default — the safe failure mode). This was a static holder in {@code utils/}; it's a
 * stateful {@code @Singleton} service (PAR-305), so the permission checks that consult it are
 * injected rather than reached statically.
 */
public interface AdminBypassService {

    /** Whether {@code player} has switched their admin bypass OFF (playing as a normal player). */
    boolean isDisabled(Player player);

    /** Flips the bypass state and returns the new value ({@code true} = bypass now disabled). */
    boolean toggle(Player player);

    /** Drop a player's state on quit so the set doesn't grow without bound. */
    void forget(UUID playerId);

    /**
     * Whether {@code sender} holds {@code node} — but an opted-out admin is denied the elevated
     * ({@linkplain Permissions#isGated gated}) nodes, demoting them to a normal player.
     */
    boolean has(CommandSender sender, String node);

    boolean otherName(Player player, String name);

    boolean otherName(Player player, String base, String name);
}
