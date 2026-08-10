package io.paradaux.chestshop.model;

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
import static io.paradaux.chestshop.model.Transaction.TransactionType.SELL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the {@link Transaction} carrier: copy-construction from a {@link PendingTransaction},
 * the per-trade nonce, the settle-step mutations (sales tax with its null guard, settlement
 * txn id) and the cancel flag.
 */
class TransactionTest extends ServerTest {

    private final Sign sign = Mockito.mock(Sign.class);
    private final Account owner = new Account("Owner", UUID.randomUUID());

    private Transaction build(boolean unlimited) {
        Player client = player("Steve");
        Inventory ownerInv = chest(27);
        Inventory clientInv = chest(9);
        ItemStack[] stock = {item(Material.DIAMOND, 4)};
        PendingTransaction pending = new PendingTransaction(
                ownerInv, clientInv, stock, new BigDecimal("10"), client, owner, sign, SELL, unlimited);
        return new Transaction(pending, sign);
    }

    @Test
    void copiesEveryFieldFromPendingTransaction() {
        Player client = player("Steve");
        Inventory ownerInv = chest(27);
        Inventory clientInv = chest(9);
        ItemStack[] stock = {item(Material.DIAMOND, 4)};
        BigDecimal price = new BigDecimal("10");
        PendingTransaction pending = new PendingTransaction(
                ownerInv, clientInv, stock, price, client, owner, sign, BUY, true);

        Transaction txn = new Transaction(pending, sign);

        assertThat(txn.getTransactionType()).isEqualTo(BUY);
        assertThat(txn.getOwnerInventory()).isSameAs(ownerInv);
        assertThat(txn.getClientInventory()).isSameAs(clientInv);
        assertThat(txn.isUnlimitedOwner()).isTrue();
        assertThat(txn.getClient()).isSameAs(client);
        assertThat(txn.getOwnerAccount()).isSameAs(owner);
        assertThat(txn.getStock()).isSameAs(stock);
        assertThat(txn.getExactPrice()).isEqualByComparingTo(price);
        assertThat(txn.getSign()).isSameAs(sign);
    }

    @Test
    void tradeId_isGenerated_andStable() {
        Transaction txn = build(false);
        UUID id = txn.getTradeId();
        assertThat(id).isNotNull();
        assertThat(txn.getTradeId()).isSameAs(id);
    }

    @Test
    void salesTax_defaultsToZero() {
        assertThat(build(false).getSalesTax()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void setSalesTax_withValue_isStored() {
        Transaction txn = build(false);
        txn.setSalesTax(new BigDecimal("1.25"));
        assertThat(txn.getSalesTax()).isEqualByComparingTo("1.25");
    }

    @Test
    void setSalesTax_withNull_fallsBackToZero() {
        Transaction txn = build(false);
        txn.setSalesTax(new BigDecimal("1.25"));
        txn.setSalesTax(null);
        assertThat(txn.getSalesTax()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void settlementTxnId_defaultsNull_andIsSettable() {
        Transaction txn = build(false);
        assertThat(txn.getSettlementTxnId()).isNull();
        txn.setSettlementTxnId(99L);
        assertThat(txn.getSettlementTxnId()).isEqualTo(99L);
    }

    @Test
    void cancelled_defaultsFalse_andToggles() {
        Transaction txn = build(false);
        assertThat(txn.isCancelled()).isFalse();
        txn.setCancelled(true);
        assertThat(txn.isCancelled()).isTrue();
    }

    @Test
    void transactionType_enumIsResolvable() {
        assertThat(Transaction.TransactionType.valueOf("SELL")).isEqualTo(SELL);
        assertThat(Transaction.TransactionType.values()).containsExactly(BUY, SELL);
    }
}
