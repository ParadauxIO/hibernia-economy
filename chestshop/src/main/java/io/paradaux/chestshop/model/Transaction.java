package io.paradaux.chestshop.model;

import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The mutable carrier for a completed shop trade, threaded through the
 * post-transaction pipeline run by {@link io.paradaux.chestshop.services.TransactionService#process}.
 * Formerly a Bukkit event fired to a chain of priority-ordered listeners; those
 * reactions (goods+money execution, empty-shop cleanup, logging, messaging, market
 * sync, metrics) are now invoked directly as ordered service steps, so this is a
 * plain transaction context/result — {@code TransactionService} may {@link #setCancelled}
 * it if the atomic goods/money leg fails, and the {@code ignoreCancelled} reactions
 * are guarded on that flag.
 *
 * @author Acrobot
 */
public class Transaction {
    private final TransactionType type;

    private final Inventory ownerInventory;
    private final Inventory clientInventory;
    // An unlimited admin shop: no owner container (infinite stock/space) — the trade moves
    // items on the client side only.
    private final boolean unlimitedOwner;

    private final Player client;
    private final Account ownerAccount;

    private final ItemStack[] stock;
    private final BigDecimal exactPrice;

    private final Sign sign;

    // A per-trade nonce, generated once when this trade context is created and
    // stable for the life of the object. Used to derive deterministic Treasury
    // idempotency (dedup) keys for the money legs (ADT-129): a re-processing of
    // the SAME trade reuses this id so the ledger's UNIQUE dedup constraint
    // collapses it, while two distinct trades — even with identical buyer/price —
    // get different ids and are settled independently. (nanoTime, the previous
    // anchor, was unique on every call and so could never dedup a double-fire.)
    private final UUID tradeId = UUID.randomUUID();

    // Sales tax actually collected on this trade's money leg, set by the settle
    // step and read by the market-analytics recorder so the market dataset reflects
    // the real tax instead of a hard-coded zero (ADT-130). Defaults to zero (no tax).
    private BigDecimal salesTax = BigDecimal.ZERO;

    // The ledger txn id of this trade's primary money leg (the buyer→seller transfer,
    // or the faucet/sink leg for an admin shop), set by the settle step and read by the
    // market-analytics recorder so a recorded sale links back to its ledger movement
    // (PAR-234). Null when nothing moved (both sides admin) or settlement failed.
    private Long settlementTxnId;

    private boolean cancelled = false;

    public Transaction(PendingTransaction event, Sign sign) {
        this.type = event.getTransactionType();

        this.ownerInventory = event.getOwnerInventory();
        this.clientInventory = event.getClientInventory();
        this.unlimitedOwner = event.isUnlimitedOwner();

        this.client = event.getClient();
        this.ownerAccount = event.getOwnerAccount();

        this.stock = event.getStock();
        this.exactPrice = event.getExactPrice();

        this.sign = sign;
    }

    /**
     * @return a stable per-trade nonce used to derive deterministic Treasury
     *         idempotency keys for this trade's money legs (ADT-129)
     */
    public UUID getTradeId() {
        return tradeId;
    }

    /** @return the sales tax collected on this trade (ADT-130); zero if none. */
    public BigDecimal getSalesTax() {
        return salesTax;
    }

    /** @return the ledger txn id of this trade's primary money leg (PAR-234), or null if none/unlinkable. */
    public Long getSettlementTxnId() {
        return settlementTxnId;
    }

    public void setSettlementTxnId(Long settlementTxnId) {
        this.settlementTxnId = settlementTxnId;
    }

    public void setSalesTax(BigDecimal salesTax) {
        this.salesTax = salesTax != null ? salesTax : BigDecimal.ZERO;
    }

    /**
     * @return Type of the transaction
     */
    public TransactionType getTransactionType() {
        return type;
    }

    /**
     * @return Owner's inventory
     */
    public Inventory getOwnerInventory() {
        return ownerInventory;
    }

    /**
     * @return whether this is an unlimited admin shop — no owner container, infinite stock and
     *         space, so the trade moves items on the client side only
     */
    public boolean isUnlimitedOwner() {
        return unlimitedOwner;
    }

    /**
     * @return Client's inventory
     */
    public Inventory getClientInventory() {
        return clientInventory;
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
     * @return Stock available
     */
    public ItemStack[] getStock() {
        return stock;
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
     * @return Shop's sign
     */
    public Sign getSign() {
        return sign;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /**
     * Possible transaction types
     */
    public enum TransactionType {
        BUY,
        SELL
    }
}
