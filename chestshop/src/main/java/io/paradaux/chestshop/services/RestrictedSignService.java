package io.paradaux.chestshop.services;

import io.paradaux.chestshop.model.PendingTransaction;
import org.bukkit.Location;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;

/**
 * The business logic behind ChestShop's {@code [restricted]} access signs: resolving the
 * restricted sign attached to a block, deciding whether a player may access / destroy / create
 * one, and gating a shop trade on it. Extracted from {@code RestrictedSignListener} so the
 * Bukkit event handlers stay a thin entrypoint and the service layer no longer injects a
 * listener (chestshop/structure/0002). The former {@code @LOW} pre-transaction gate
 * ({@link #onPreTransaction}) is invoked directly by {@code TransactionService}.
 */
public interface RestrictedSignService {

    /** Cancel a trade whose shop carries a {@code [restricted]} sign the client can't access. */
    void onPreTransaction(PendingTransaction event);

    /** Resolve the {@code [restricted]} sign attached to the block at {@code location}, or null. */
    Sign getRestrictedSign(Location location);

    /** Whether {@code player} may trade at the shop under {@code sign}. */
    boolean canAccess(Sign sign, Player player);

    /** Whether {@code player} may destroy the restricted {@code sign} (via its associated shop). */
    boolean canDestroy(Player player, Sign sign);

    /** Whether {@code player} passes the group permission gate for the given restricted-sign lines. */
    boolean hasPermission(Player player, String[] lines);
}
