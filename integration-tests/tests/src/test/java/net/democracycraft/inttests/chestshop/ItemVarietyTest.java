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
 * The item leg isn't hard-coded to DIRT: a shop trading a different vanilla
 * material resolves it end-to-end (sign item line → item code → chest match →
 * the goods that move). Guards against the item-resolution path only being
 * exercised for one material.
 */
class ItemVarietyTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int QTY = 3;
    private static final int PRICE = 250;

    @Test
    void buyResolvesADifferentVanillaMaterial() {
        String seller = Usernames.fresh("Ivs");
        String buyer = Usernames.fresh("Ivb");

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            shop.setupSellShop(seller, QTY, PRICE, "diamond");
            int initialStock = Inv.chestCount(shop.chestPos(), "DIAMOND");
            assertThat(initialStock).isGreaterThanOrEqualTo(QTY);
            RconClient.send("diag warmname " + seller);

            shop.clickBuy(b, buyer);

            Ledger.waitForPersonalBalance(b.getUuid(),
                    STARTING.subtract(BigDecimal.valueOf(PRICE)), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(),
                    STARTING.add(BigDecimal.valueOf(PRICE)), TIMEOUT);
            Inv.waitForPlayerCount(buyer, "DIAMOND", QTY, TIMEOUT);
            Inv.waitForChestCount(shop.chestPos(), "DIAMOND", initialStock - QTY, TIMEOUT);
            // The buyer got diamonds, not dirt.
            assertThat(Inv.playerCount(buyer, "DIRT")).isZero();
        }
    }
}
