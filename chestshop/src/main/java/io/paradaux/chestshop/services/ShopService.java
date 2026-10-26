package io.paradaux.chestshop.services;

import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.model.ShopCreation;
import io.paradaux.chestshop.model.CreatedShop;
import io.paradaux.chestshop.model.DestroyedShop;
import org.bukkit.Bukkit;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;

/**
 * Owns the whole shop lifecycle: creation validation ({@link #create}), the post-creation
 * and post-removal reactions ({@link #onCreated}/{@link #onDestroyed}), and the
 * creation-fee / removal-refund money (with the mirrored server-economy movement).
 * This replaces ChestShop's old event-bus design — a {@code ShopCreation} fanned
 * out to a dozen priority-ordered validator classes coordinating through a mutable
 * outcome/signLines bag — with one service whose validation steps are ordinary ordered
 * private methods (PAR-282). The genuine cross-cutting hooks (market-DB sync, stock
 * counter) and {@code Security}/{@code ProtectionService} integration stay.
 *
 * <p>Shops have no ChestShop-owned persistence — they are sign + chest world state — so
 * there is no repository here, only this service.
 */
public interface ShopService {

    /**
     * Run a shop-sign creation through the validation steps and return the result context
     * for the caller ({@code SignCreateListener}) to act on. The steps run in the exact order the
     * former priority-ordered validators fired; like the originals (almost all
     * {@code ignoreCancelled=false}) each runs unconditionally and may overwrite the
     * outcome — so the reported failure is the last problem found. The two former
     * {@code ignoreCancelled=true} steps (the creation-fee charge and the second name
     * pass) run only while the creation is still un-cancelled.
     */
    ShopCreation create(Player player, Sign sign, String[] signLines);

    /**
     * Run the post-creation reactions for a freshly-created shop, in the exact priority
     * + registration order the former {@link CreatedShop} listeners fired in
     * (replacing the Bukkit event dispatch): NORMAL {@code SignSticker} sticks the sign
     * to its chest, then MONITOR {@code MessageSender} notifies the creator,
     * {@code ShopCreationLogger} logs it, and {@code MarketSyncService} upserts the shop in
     * the market registry.
     */
    void onCreated(CreatedShop event);

    /**
     * Run the post-removal reactions for a removed shop, in the former {@link DestroyedShop}
     * listener order (all MONITOR): issue the removal refund, log the removal, then mark
     * the shop inactive in the market registry.
     */
    void onDestroyed(DestroyedShop event);

    /**
     * Charge the configured creation fee to {@code player} for a shop with the given
     * sign lines. Returns {@code true} if creation may proceed (fee paid, or none is
     * due — zero price, admin shop, or the {@code NOFEE} permission), {@code false} if
     * the player could not afford it (the caller should fail the creation).
     */
    boolean chargeCreationFee(Player player, String[] signLines);

    /**
     * Refund the configured removal price to the shop's owner when {@code destroyer}
     * breaks it, mirroring the deduction from the server-economy account. No-ops when
     * there is no refund due (no destroyer, {@code NOFEE}, zero price, an autofill
     * placeholder sign, or an unknown owner account).
     */
    void refundOnRemoval(Player destroyer, Sign sign);
}
