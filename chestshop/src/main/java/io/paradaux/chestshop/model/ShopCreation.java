package io.paradaux.chestshop.model;

import org.bukkit.block.Sign;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;

/**
 * The mutable carrier threaded through the shop-creation validation steps run by
 * {@link io.paradaux.chestshop.services.ShopService#create}. Formerly a Bukkit event
 * fired to a pipeline of priority-ordered listeners; those listeners are now invoked
 * directly as ordered service steps, so this is a plain creation context/result —
 * the validators read/mutate its {@link #outcome}, {@link #signLines} and
 * {@link #ownerAccount}, and {@code ShopService} returns it for the caller to act on.
 *
 * @author Acrobot
 */
public class ShopCreation {

    // Immutable creation inputs.
    private final Player creator;
    private final Sign sign;

    // Computed by the creation steps: the outcome, the resolved owner account, and the
    // rewritten sign lines (owner-name/business-token resolution, autofill).
    @Nullable private Account ownerAccount = null;
    private CreationOutcome outcome = CreationOutcome.SHOP_CREATED_SUCCESSFULLY;
    private String[] signLines;

    public ShopCreation(Player creator, Sign sign, String[] signLines) {
        this.creator = creator;
        this.sign = sign;
        this.signLines = signLines.clone();
    }

    /**
     * Returns if event is cancelled
     *
     * @return Is event cancelled?
     */
    public boolean isCancelled() {
        return outcome != CreationOutcome.SHOP_CREATED_SUCCESSFULLY;
    }

    /**
     * Set if event is cancelled. This sets a generic {@link CreationOutcome#OTHER};
     *
     * @param cancel Cancel the event?
     */
    public void setCancelled(boolean cancel) {
        if (cancel) {
            outcome = CreationOutcome.OTHER;
        } else {
            outcome = CreationOutcome.SHOP_CREATED_SUCCESSFULLY;
        }
    }

    /**
     * Returns the outcome of the event
     *
     * @return Event's outcome
     */
    public CreationOutcome getOutcome() {
        return outcome;
    }

    /**
     * Sets the event's outcome
     *
     * @param outcome Outcome
     */
    public void setOutcome(CreationOutcome outcome) {
        this.outcome = outcome;
    }

    /**
     * Sets the text on the sign
     *
     * @param signLines Text to set
     */
    public void setSignLines(String[] signLines) {
        // Clone so the event doesn't alias the caller's array; mutating it
        // afterwards must not retroactively change the validated payload (and
        // the constructor already clones, so this keeps the two paths matched).
        this.signLines = signLines.clone();
    }

    /**
     * Sets one of the lines on the sign
     *
     * @param line Line number to set (0-3)
     * @param text Text to set
     */
    public void setSignLine(byte line, String text) {
        this.signLines[line] = text;
    }

    /**
     * Returns the shop's creator
     *
     * @return Shop's creator
     */
    public Player getPlayer() {
        return creator;
    }

    /**
     * Returns the shop's sign
     *
     * @return Shop's sign
     */
    public Sign getSign() {
        return sign;
    }

    /**
     * Returns the text on the sign
     *
     * @param line Line number (0-3)
     * @return Text on the sign
     */
    public String getSignLine(byte line) {
        return signLines[line];
    }

    /**
     * Returns the text on the sign
     *
     * @return Text on the sign
     */
    public String[] getSignLines() {
        return signLines;
    }

    /**
     * Get the account of the shop owner
     *
     * @return the Account of the shop owner; null if not found
     */
    @Nullable
    public Account getOwnerAccount() {
        return ownerAccount;
    }

    /**
     * Set the account of the shop owner
     *
     * @param ownerAccount the Account of the shop owner
     */
    public void setOwnerAccount(@Nullable Account ownerAccount) {
        this.ownerAccount = ownerAccount;
    }

    /**
     * Possible outcomes
     */
    public enum CreationOutcome {
        INVALID_ITEM,
        INVALID_PRICE,
        INVALID_QUANTITY,

        ITEM_AUTOFILL(false),

        UNKNOWN_PLAYER,

        SELL_PRICE_HIGHER_THAN_BUY_PRICE,

        NO_CHEST,

        NO_PERMISSION,
        NO_PERMISSION_FOR_TERRAIN,
        NO_PERMISSION_FOR_CHEST,

        NOT_ENOUGH_MONEY,

        /**
         * For plugin use
         */
        OTHER(false),
        /**
         * Break the sign
         */
        OTHER_BREAK,

        SHOP_CREATED_SUCCESSFULLY(false);

        private final boolean breakSign;

        CreationOutcome() {
            this.breakSign = true;
        }

        CreationOutcome(boolean breakSign) {
            this.breakSign = breakSign;
        }

        /**
         * Get whether or not this outcome should result in the shop sign getting broken
         *
         * @return Whether or not the shop sign gets broken
         */
        public boolean shouldBreakSign() {
            return breakSign;
        }
    }
}
