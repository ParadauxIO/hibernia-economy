package io.paradaux.chestshop.services;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.hibernia.framework.i18n.Message;
import java.util.Arrays;
import java.util.UUID;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;

/** A single-use offer. Confirmation rebuilds the trade against live stock, permissions and prices. */
@Singleton
public final class PurchasePreviewService {
    private final TransactionService transactions;
    private final AccountService accounts;
    private final ProtectionService protection;
    private final SignService signs;
    private final ChestShopConfiguration config;
    private final Message message;
    private final MaterialService materials;

    @Inject
    public PurchasePreviewService(
            TransactionService transactions,
            AccountService accounts,
            ProtectionService protection,
            SignService signs,
            ChestShopConfiguration config,
            Message message,
            MaterialService materials) {
        this.materials = materials;
        this.transactions = transactions;
        this.accounts = accounts;
        this.protection = protection;
        this.signs = signs;
        this.config = config;
        this.message = message;
    }

    public static final class Offer {
        private final UUID player;
        private final Block block;
        private final String owner;
        private final String item;
        private final String priceLine;
        private final int quantity;
        private final Action action;
        private final ItemStack selected;
        private final ItemStack[] stock;
        private final java.math.BigDecimal price;
        private final boolean buying;
        private boolean consumed;

        private Offer(PendingTransaction trade, Action action, ItemStack selected) {
            buying = trade.getTransactionType() == Transaction.TransactionType.BUY;
            player = trade.getClient().getUniqueId();
            block = trade.getSign().getBlock();
            owner = SignService.getOwner(trade.getSign());
            item = SignService.getItem(trade.getSign());
            priceLine = SignService.getPrice(trade.getSign());
            quantity = SignService.getQuantity(trade.getSign());
            this.action = action;
            this.selected = selected == null ? null : selected.clone();
            stock = Arrays.stream(trade.getStock()).map(ItemStack::clone).toArray(ItemStack[]::new);
            price = trade.getExactPrice();
        }

        public boolean buying() {
            return buying;
        }

        public ItemStack[] stock() {
            return Arrays.stream(stock).map(ItemStack::clone).toArray(ItemStack[]::new);
        }

        public java.math.BigDecimal price() {
            return price;
        }
    }

    public Offer preview(Sign sign, Player player, Action action) {
        return preview(sign, player, action, null);
    }

    public Offer preview(Sign sign, Player player, Action action, ItemStack selected) {
        if (!canUse(sign, player)) return null;
        PendingTransaction trade = selected == null
                ? transactions.prepare(sign, player, action)
                : transactions.prepare(sign, player, action, selected);
        if (trade == null) return null;
        transactions.validate(trade);
        if (trade.isCancelled()) return null;
        if (!exactInventoryItems(trade)) {
            message.send(player, "shop.purchase.metadata-conflict");
            return null;
        }
        return new Offer(trade, action, selected);
    }

    public boolean confirm(Offer offer, Player player) {
        if (offer.consumed || !offer.player.equals(player.getUniqueId())) return false;
        offer.consumed = true;
        if (!(offer.block.getState() instanceof Sign sign) || !canUse(sign, player)) return changed(player);
        if (!offer.owner.equals(SignService.getOwner(sign))
                || !offer.item.equals(SignService.getItem(sign))
                || !offer.priceLine.equals(SignService.getPrice(sign))
                || offer.quantity != SignService.getQuantity(sign)) {
            return changed(player);
        }
        PendingTransaction trade = offer.selected == null
                ? transactions.prepare(sign, player, offer.action)
                : transactions.prepare(sign, player, offer.action, offer.selected);
        if (trade == null) return false;
        transactions.validate(trade);
        if (trade.isCancelled()) return false;
        if ((trade.getTransactionType() == Transaction.TransactionType.BUY) != offer.buying
                || trade.getExactPrice().compareTo(offer.price) != 0
                || !Arrays.equals(trade.getStock(), offer.stock)) {
            return changed(player);
        }
        if (!exactInventoryItems(trade)) return changed(player);
        Transaction transaction = new Transaction(trade, sign);
        transactions.process(transaction);
        return !transaction.isCancelled();
    }

    // Configured ignored attributes can make the legacy transfer combine different metadata.
    // Refuse ambiguous inventories rather than show one item and silently transfer another.
    private boolean exactInventoryItems(PendingTransaction trade) {
        for (ItemStack expected : trade.getStock()) {
            for (org.bukkit.inventory.Inventory inventory : new org.bukkit.inventory.Inventory[] {
                trade.isUnlimitedOwner() ? null : trade.getOwnerInventory(), trade.getClientInventory()
            }) {
                if (inventory == null) continue;
                for (ItemStack actual : inventory.getContents()) {
                    if (actual != null
                            && !actual.getType().isAir()
                            && materials.equals(actual, expected)
                            && !actual.isSimilar(expected)) return false;
                }
            }
        }
        return true;
    }

    public boolean canUse(Sign sign, Player player) {
        if (!player.isOnline()
                || !player.getWorld().equals(sign.getWorld())
                || player.getLocation().distanceSquared(sign.getLocation()) > 64
                || !signs.isValid(sign)) return false;
        if (!signs.isAdminShop(sign)
                && (accounts.isOwner(player, sign)
                        || (config.isIgnoreAccessPerms() && accounts.canAccess(player, sign)))) return false;
        return !config.isCheckAccessForShopUse() || protection.canAccess(sign.getBlock(), player, true);
    }

    private boolean changed(Player player) {
        message.send(player, "shop.purchase.changed");
        return false;
    }
}
