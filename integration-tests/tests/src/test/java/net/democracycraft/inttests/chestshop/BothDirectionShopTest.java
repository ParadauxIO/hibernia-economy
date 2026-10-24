package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Inv;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A dual-price shop (sign line {@code "<buy> B <sell> S"}) supports both
 * directions against the one shop: a right-click buys and a left-click sells.
 * The client buys a batch, then sells it back, and the money/stock return to a
 * predictable net.
 */
class BothDirectionShopTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");

    @Test
    void buyThenSellBackAgainstOneShop() {
        String owner = Usernames.fresh("Bdo");
        String client = Usernames.fresh("Bdc");
        int qty = 5;
        int buyPrice = 100;
        int sellPrice = 40;

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer c = MockPlayer.connectLocal(client);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(o.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(c.getUuid(), STARTING, TIMEOUT);

            shop.setupBothShop(owner, qty, buyPrice, sellPrice, "dirt");
            int initialStock = Inv.chestCount(shop.chestPos(), "DIRT");
            assertThat(initialStock).isGreaterThanOrEqualTo(qty);

            // Buy: client -buyPrice, owner +buyPrice, chest down qty, client up qty.
            shop.clickBuy(c, client);
            Ledger.waitForPersonalBalance(c.getUuid(),
                    STARTING.subtract(BigDecimal.valueOf(buyPrice)), TIMEOUT);
            Ledger.waitForPersonalBalance(o.getUuid(),
                    STARTING.add(BigDecimal.valueOf(buyPrice)), TIMEOUT);
            Inv.waitForPlayerCount(client, "DIRT", qty, TIMEOUT);
            Inv.waitForChestCount(shop.chestPos(), "DIRT", initialStock - qty, TIMEOUT);

            // Sell it back: client +sellPrice on top, owner -sellPrice, stock restored.
            shop.clickSell(c, client);
            Ledger.waitForPersonalBalance(c.getUuid(),
                    STARTING.subtract(BigDecimal.valueOf(buyPrice)).add(BigDecimal.valueOf(sellPrice)), TIMEOUT);
            Ledger.waitForPersonalBalance(o.getUuid(),
                    STARTING.add(BigDecimal.valueOf(buyPrice)).subtract(BigDecimal.valueOf(sellPrice)), TIMEOUT);
            Inv.waitForPlayerCount(client, "DIRT", 0, TIMEOUT);
            Inv.waitForChestCount(shop.chestPos(), "DIRT", initialStock, TIMEOUT);
        }
    }
}
