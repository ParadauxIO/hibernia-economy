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
 * Real shop creation — the flow the rest of the suite bypasses by hand-placing shops
 * with {@code /setblock}. Here a shop is created the way a player does it, by firing
 * ChestShop's SignCreate (SignChangeEvent) through the full create pipeline
 * (validation, canBuild, fee, registration). The created shop is then exercised to
 * prove it's a real, recognised shop.
 *
 * <p>NOTE on hopper protection (not asserted here): ChestShop's ItemMoveListener only
 * cancels a pull when the destination holder is NOT a BlockState — i.e. a hopper
 * MINECART, not a hopper block (upstream Acrobot logic, ItemMoveListener.java:36). A
 * plain hopper block below a shop chest is therefore not blocked by this listener (it's
 * assumed block-place protection stops the hopper being placed). The real minecart
 * path is covered by HopperMinecartProtectionTest, using the same cart before and
 * after sign removal to prove that transfer protection is operational.
 */
class ShopCreationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int QTY = 5;
    private static final int PRICE = 100;

    @Test
    void createdShopIsRealAndTradeable() {
        String seller = Usernames.fresh("Ccs");
        String buyer = Usernames.fresh("Ccb");

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            String resp = shop.createSellShop(seller, seller, QTY, PRICE, "dirt");
            assertThat(resp).as("real shop creation should succeed").contains("cancelled=false");
            int initialStock = Inv.chestCount(shop.chestPos(), "DIRT");
            RconClient.send("diag warmname " + seller);

            // Trade against the created shop — works only if it's a real, recognised shop.
            shop.clickBuy(b, buyer);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING.subtract(BigDecimal.valueOf(PRICE)), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(), STARTING.add(BigDecimal.valueOf(PRICE)), TIMEOUT);
            Inv.waitForPlayerCount(buyer, "DIRT", QTY, TIMEOUT);
            Inv.waitForChestCount(shop.chestPos(), "DIRT", initialStock - QTY, TIMEOUT);
        }
    }

}
