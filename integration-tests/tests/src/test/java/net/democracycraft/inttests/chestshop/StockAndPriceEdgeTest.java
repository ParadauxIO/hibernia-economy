package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Inv;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stock/price edge cases: partial fulfilment (ADT-138) — a chest holding less
 * than a full batch sells what it has, pro-rata, so the buyer pays for exactly
 * the goods received rather than the full sign quantity.
 *
 * <p>NOTE: a free-shop case (price {@code 0 B}) is deliberately omitted. On a
 * live server a buyer right-clicking a {@code 0 B} shop had its <em>sign
 * removed</em> ("shop ... was removed by &lt;buyer&gt;") instead of trading —
 * anomalous behaviour specific to zero-price shops that wants a separate
 * investigation before it's asserted here.
 */
class StockAndPriceEdgeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int QTY = 5;
    private static final int PRICE = 100; // → 20 per item

    @Test
    void partialFillSellsAvailableStockProRata() {
        String seller = Usernames.fresh("Pfs");
        String buyer = Usernames.fresh("Pfb");
        int available = QTY - 1;                     // 4 of 5
        BigDecimal expectedCost = BigDecimal.valueOf((long) PRICE * available / QTY); // 4 * 20 = 80

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            shop.setupSellShop(seller, QTY, PRICE, "dirt");
            RconClient.send(String.format(
                    "item replace block %d %d %d container.0 with minecraft:dirt %d",
                    shop.chestPos().x(), shop.chestPos().y(), shop.chestPos().z(), available));
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(available);
            RconClient.send("diag warmname " + seller);

            shop.clickBuy(b, buyer);

            // Buyer got the 4 available, paid pro-rata (80); seller credited 80; chest emptied.
            Inv.waitForPlayerCount(buyer, "DIRT", available, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING.subtract(expectedCost), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(), STARTING.add(expectedCost), TIMEOUT);
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isZero();
        }
    }

    @Test
    void partialFillLimitedByBuyerBalance() {
        String seller = Usernames.fresh("Pms");
        String buyer = Usernames.fresh("Pmb");
        int affordItems = 3;                          // buyer can afford 3 of 5
        BigDecimal budget = BigDecimal.valueOf((long) PRICE / QTY * affordItems); // 3 * 20 = 60

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            shop.setupSellShop(seller, QTY, PRICE, "dirt"); // plenty of stock
            int initialStock = Inv.chestCount(shop.chestPos(), "DIRT");
            RconClient.send("diag warmname " + seller);
            RconClient.send("eco set " + buyer + " " + budget.toPlainString());
            Ledger.waitForPersonalBalance(b.getUuid(), budget, TIMEOUT);

            shop.clickBuy(b, buyer);

            // Partial fill capped by what the buyer can afford: 3 items for 60, balance to 0.
            Inv.waitForPlayerCount(buyer, "DIRT", affordItems, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), BigDecimal.ZERO, TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(), STARTING.add(budget), TIMEOUT);
            Inv.waitForChestCount(shop.chestPos(), "DIRT", initialStock - affordItems, TIMEOUT);
        }
    }

    @Test
    void buyRejectedWhenBalanceBelowUnitPrice() {
        String seller = Usernames.fresh("Sus");
        String buyer = Usernames.fresh("Sub");
        BigDecimal budget = new BigDecimal("10"); // unit price is 20 → can't afford even one item

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            shop.setupSellShop(seller, QTY, PRICE, "dirt");
            int initialStock = Inv.chestCount(shop.chestPos(), "DIRT");
            RconClient.send("diag warmname " + seller);
            RconClient.send("eco set " + buyer + " " + budget.toPlainString());
            Ledger.waitForPersonalBalance(b.getUuid(), budget, TIMEOUT);

            shop.clickBuy(b, buyer);
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // Can't afford a single item → nothing happens.
            assertThat(Ledger.personalBalance(b.getUuid()).orElseThrow()).isEqualByComparingTo(budget);
            assertThat(Ledger.personalBalance(s.getUuid()).orElseThrow()).isEqualByComparingTo(STARTING);
            assertThat(Inv.playerCount(buyer, "DIRT")).isZero();
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(initialStock);
        }
    }
}
