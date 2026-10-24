package io.paradaux.chestshop.api.events;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.services.SettledPurchasePublisher;
import io.paradaux.chestshop.support.ServerTest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.event.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class SettledPurchasePublisherTest extends ServerTest {
    @Test
    void publishesOnlySuccessfulBuyWithStableIdentityAndDetachedSnapshot() {
        var received = new ArrayList<ChestShopPurchaseEvent>();
        var plugin = MockBukkit.createMockPlugin();
        server.getPluginManager()
                .registerEvents(
                        new Listener() {
                            @EventHandler
                            public void purchase(ChestShopPurchaseEvent event) {
                                received.add(event);
                            }
                        },
                        plugin);
        var buyer = player("Buyer");
        var trade = mock(Transaction.class);
        var id = UUID.randomUUID();
        var goods = new ItemStack[] {item(Material.STONE, 3)};
        when(trade.getClient()).thenReturn(buyer);
        when(trade.getTradeId()).thenReturn(id);
        when(trade.getExactPrice()).thenReturn(new BigDecimal("1.25"));
        when(trade.getStock()).thenReturn(goods);
        when(trade.getTransactionType()).thenReturn(Transaction.TransactionType.BUY);
        var publisher = new SettledPurchasePublisher();
        publisher.publish(trade);
        publisher.publish(trade);
        assertThat(received).hasSize(2);
        var receipt = received.getFirst().getReceipt();
        assertThat(receipt.tradeId()).isEqualTo(id);
        assertThat(received.getLast().getReceipt().tradeId()).isEqualTo(id);
        assertThat(receipt.buyerId()).isEqualTo(buyer.getUniqueId());
        assertThat(receipt.price()).isEqualByComparingTo("1.25");
        assertThat(receipt.items()).containsExactly(new PurchaseReceipt.PurchasedItem(Material.STONE, 3));
        goods[0].setAmount(1);
        assertThat(receipt.items().getFirst().quantity()).isEqualTo(3);
        assertThatThrownBy(() -> receipt.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(received.getFirst()).isNotInstanceOf(Cancellable.class);
        when(trade.getTransactionType()).thenReturn(Transaction.TransactionType.SELL);
        publisher.publish(trade);
        when(trade.getTransactionType()).thenReturn(Transaction.TransactionType.BUY);
        when(trade.isCancelled()).thenReturn(true);
        publisher.publish(trade);
        assertThat(received).hasSize(2);
    }

    @Test
    void receiptRejectsInvalidGoodsAndDoesNotRetainMutableList() {
        var goods = new ArrayList<>(List.of(new PurchaseReceipt.PurchasedItem(Material.STONE, 1)));
        var receipt = new PurchaseReceipt(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ZERO, goods);
        goods.clear();
        assertThat(receipt.items()).hasSize(1);
        assertThatThrownBy(() -> new PurchaseReceipt.PurchasedItem(Material.STONE, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PurchaseReceipt.PurchasedItem(Material.AIR, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PurchaseReceipt(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ZERO, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
