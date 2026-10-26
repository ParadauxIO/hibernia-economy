package io.paradaux.chestshop.model;

import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;

import static io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome.TRANSACTION_SUCCESSFUL;
import static io.paradaux.chestshop.model.Transaction.TransactionType;

/**
 * Carrier threaded through the ordered pre-transaction validation steps run by
 * {@link io.paradaux.chestshop.services.TransactionService#validate}. The trade inputs
 * (client, owner account, inventories, sign, type) are immutable; the steps only compute
 * the {@linkplain #getTransactionOutcome() outcome}, optionally resize the
 * {@linkplain #getStock() stock}/{@linkplain #getExactPrice() price} for a partial fill,
 * and flag a {@linkplain #isRejectedAsFreeShop() free-shop rejection}. Formerly a Bukkit
 * event fired to a pipeline of priority-ordered validators (PAR-282).
 *
 * @author Acrobot
 */
public class PendingTransaction {

    // Immutable trade inputs (from TransactionService#prepare).
    private final Player client;
    private final Account ownerAccount;
    private final TransactionType transactionType;
    private final Sign sign;
    private final Inventory ownerInventory;
    private final Inventory clientInventory;

    // An unlimited admin shop: no owner container (infinite stock/space), so the owner side of
    // stock/space validation is skipped and the trade moves items on the client side only.
    private final boolean unlimitedOwner;

    // The only state the validation steps compute: the outcome (verdict), an optional
    // partial-fill resize of the stock + price, and the deferred free-shop rejection flag.
    private ItemStack[] items;
    private BigDecimal exactPrice;
    private TransactionOutcome transactionOutcome = TRANSACTION_SUCCESSFUL;

    // Set by the (side-effect-free) free-shop validation step when a legacy price-0 shop
    // is rejected, so the destructive removal runs exactly once after validation finishes
    // rather than mutating the world mid-validation (ADT-139).
    private boolean rejectedAsFreeShop = false;

    public PendingTransaction(Inventory ownerInventory, Inventory clientInventory, ItemStack[] items, BigDecimal exactPrice, Player client, Account ownerAccount, Sign sign, TransactionType type, boolean unlimitedOwner) {
        this.ownerInventory = ownerInventory;
        this.clientInventory = (clientInventory == null ? client.getInventory() : clientInventory);

        this.items = items;
        this.exactPrice = exactPrice;

        this.client = client;
        this.ownerAccount = ownerAccount;

        this.sign = sign;
        this.transactionType = type;
        this.unlimitedOwner = unlimitedOwner;
    }

    /**
     * @return whether this is an unlimited admin shop — no owner container, infinite stock and
     *         space, so the owner side of the trade is skipped
     */
    public boolean isUnlimitedOwner() {
        return unlimitedOwner;
    }

    /** @return whether this trade was rejected because it is a legacy free (price-0) shop (ADT-139). */
    public boolean isRejectedAsFreeShop() {
        return rejectedAsFreeShop;
    }

    public void setRejectedAsFreeShop(boolean rejectedAsFreeShop) {
        this.rejectedAsFreeShop = rejectedAsFreeShop;
    }

    /**
     * @return Shop's sign
     */
    public Sign getSign() {
        return sign;
    }

    /**
     * Get the exact total price
     *
     * @return Exact total price of the items
     */
    public BigDecimal getExactPrice() {
        return exactPrice;
    }

    /**
     * Sets the exact price of the items
     *
     * @param exactPrice Price of the items
     */
    public void setExactPrice(BigDecimal exactPrice) {
        this.exactPrice = exactPrice;
    }

    /**
     * Sets the stock
     *
     * @param stock Stock
     */
    public void setStock(ItemStack... stock) {
        items = stock;
    }

    /**
     * @return Stock available
     */
    public ItemStack[] getStock() {
        return items;
    }

    /**
     * @return Shop's client
     */
    public Player getClient() {
        return client;
    }

    /**
     * @return Account of the shop's owner
     */
    public Account getOwnerAccount() {
        return ownerAccount;
    }

    /**
     * @return Owner's inventory
     */
    public Inventory getOwnerInventory() {
        return ownerInventory;
    }

    /**
     * @return Client's inventory
     */
    public Inventory getClientInventory() {
        return clientInventory;
    }

    /**
     * @return Transaction's type
     */
    public TransactionType getTransactionType() {
        return transactionType;
    }

    /**
     * @return Is the transaction cancelled?
     */
    public boolean isCancelled() {
        return transactionOutcome != TRANSACTION_SUCCESSFUL;
    }

    /**
     * @return Transaction's outcome
     */
    public TransactionOutcome getTransactionOutcome() {
        return transactionOutcome;
    }

    /**
     * Sets the outcome of the transaction
     *
     * @param reason Transction's outcome
     */
    public void setCancelled(TransactionOutcome reason) {
        transactionOutcome = reason;
    }

    public enum TransactionOutcome {
        SHOP_DOES_NOT_BUY_THIS_ITEM,
        SHOP_DOES_NOT_SELL_THIS_ITEM,

        CLIENT_DOES_NOT_HAVE_PERMISSION,

        CLIENT_DOES_NOT_HAVE_ENOUGH_MONEY,
        SHOP_DOES_NOT_HAVE_ENOUGH_MONEY,

        CLIENT_DEPOSIT_FAILED,
        SHOP_DEPOSIT_FAILED,

        NOT_ENOUGH_SPACE_IN_CHEST,
        NOT_ENOUGH_SPACE_IN_INVENTORY,

        NOT_ENOUGH_STOCK_IN_CHEST,
        NOT_ENOUGH_STOCK_IN_INVENTORY,

        INVALID_SHOP,
        INVALID_CLIENT_NAME,

        SPAM_CLICKING_PROTECTION,
        CREATIVE_MODE_PROTECTION,
        SHOP_IS_RESTRICTED,

        OTHER, //For plugin use!

        TRANSACTION_SUCCESSFUL
    }
}
