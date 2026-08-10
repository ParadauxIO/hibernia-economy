package io.paradaux.chestshop.model;

import io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome;
import io.paradaux.chestshop.support.ServerTest;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.UUID;

import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@link PendingTransaction} carrier on a live MockBukkit server: the immutable
 * trade inputs, the constructor's client-inventory fallback, the mutable outcome/price/stock
 * state, and the free-shop rejection flag.
 */
class PendingTransactionTest extends ServerTest {

    private final Sign sign = Mockito.mock(Sign.class);
    private final Account owner = new Account("Owner", UUID.randomUUID());

    private PendingTransaction pending(Player client, Inventory clientInv, boolean unlimited,
                                       ItemStack[] stock, BigDecimal price) {
        return new PendingTransaction(chest(27), clientInv, stock, price, client, owner, sign, BUY, unlimited);
    }

    @Test
    void constructor_withExplicitClientInventory_keepsIt() {
        Player client = player("Steve");
        Inventory clientInv = chest(9);
        PendingTransaction pt = pending(client, clientInv, false,
                new ItemStack[]{item(Material.DIAMOND, 4)}, new BigDecimal("10"));

        assertThat(pt.getClientInventory()).isSameAs(clientInv);
    }

    @Test
    void constructor_withNullClientInventory_fallsBackToPlayerInventory() {
        Player client = player("Alex");
        PendingTransaction pt = pending(client, null, false,
                new ItemStack[]{item(Material.DIAMOND, 4)}, new BigDecimal("10"));

        assertThat(pt.getClientInventory()).isSameAs(client.getInventory());
    }

    @Test
    void immutableInputs_areExposedVerbatim() {
        Player client = player("Steve");
        ItemStack[] stock = {item(Material.DIAMOND, 4)};
        BigDecimal price = new BigDecimal("12.50");
        PendingTransaction pt = pending(client, chest(9), true, stock, price);

        assertThat(pt.getClient()).isSameAs(client);
        assertThat(pt.getOwnerAccount()).isSameAs(owner);
        assertThat(pt.getSign()).isSameAs(sign);
        assertThat(pt.getTransactionType()).isEqualTo(BUY);
        assertThat(pt.getOwnerInventory()).isNotNull();
        assertThat(pt.getStock()).isSameAs(stock);
        assertThat(pt.getExactPrice()).isEqualByComparingTo(price);
        assertThat(pt.isUnlimitedOwner()).isTrue();
    }

    @Test
    void setStock_andSetExactPrice_updateForPartialFill() {
        PendingTransaction pt = pending(player("Steve"), chest(9), false,
                new ItemStack[]{item(Material.DIAMOND, 4)}, new BigDecimal("10"));

        ItemStack[] resized = {item(Material.DIAMOND, 2)};
        pt.setStock(resized);
        pt.setExactPrice(new BigDecimal("5"));

        assertThat(pt.getStock()).isSameAs(resized);
        assertThat(pt.getExactPrice()).isEqualByComparingTo("5");
    }

    @Test
    void newTransaction_isNotCancelled_andSuccessful() {
        PendingTransaction pt = pending(player("Steve"), chest(9), false,
                new ItemStack[]{item(Material.DIAMOND, 4)}, new BigDecimal("10"));

        assertThat(pt.isCancelled()).isFalse();
        assertThat(pt.getTransactionOutcome()).isEqualTo(TransactionOutcome.TRANSACTION_SUCCESSFUL);
    }

    @Test
    void setCancelled_withOutcome_marksCancelled() {
        PendingTransaction pt = pending(player("Steve"), chest(9), false,
                new ItemStack[]{item(Material.DIAMOND, 4)}, new BigDecimal("10"));

        pt.setCancelled(TransactionOutcome.SHOP_IS_RESTRICTED);

        assertThat(pt.isCancelled()).isTrue();
        assertThat(pt.getTransactionOutcome()).isEqualTo(TransactionOutcome.SHOP_IS_RESTRICTED);
    }

    @Test
    void rejectedAsFreeShop_defaultsFalse_andIsSettable() {
        PendingTransaction pt = pending(player("Steve"), chest(9), false,
                new ItemStack[]{item(Material.DIAMOND, 4)}, new BigDecimal("10"));

        assertThat(pt.isRejectedAsFreeShop()).isFalse();
        pt.setRejectedAsFreeShop(true);
        assertThat(pt.isRejectedAsFreeShop()).isTrue();
    }

    @Test
    void transactionOutcome_enumIsResolvable() {
        assertThat(TransactionOutcome.valueOf("OTHER")).isEqualTo(TransactionOutcome.OTHER);
        assertThat(TransactionOutcome.values()).contains(TransactionOutcome.TRANSACTION_SUCCESSFUL);
    }
}
