package io.paradaux.chestshop.services;

import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;

/**
 * The shop-sign break business logic, extracted from {@code SignBreakListener}
 * (chestshop/structure/0002): protection ({@link #canBlockBeBroken}), physics-driven removal
 * ({@link #handlePhysicsBreak}), and firing the shop-removal reaction ({@link #sendShopDestroyed}).
 * The Bukkit event handlers stay in the listener; these operations are also invoked directly by
 * {@code TransactionService} and the physics/sign-create listeners, so they live in the service
 * layer.
 *
 * @author Acrobot
 */
public interface SignBreakService {

    /** Remove the shop under a sign torn off its host block by physics. */
    void handlePhysicsBreak(Block block);

    /**
     * Whether every shop sign attached to {@code block} may be broken by {@code breaker}. As a
     * side effect, tags the breakable signs with the destroyer so the removal reaction can credit
     * the refund. Returns {@code false} (breaking nothing) if any attached shop is protected.
     */
    boolean canBlockBeBroken(Block block, Player breaker);

    /** Fire the shop-removal reaction for {@code sign}, attributing it to {@code player}. */
    void sendShopDestroyed(Sign sign, Player player);
}
