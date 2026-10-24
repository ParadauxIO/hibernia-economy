package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Inv;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Admin-shop coverage: a sign whose owner line is the configured
 * {@code ADMIN_SHOP_NAME} ("Admin Shop"). Admin shops have no owner account —
 * the counterparty money is server-side — so only the clicker's balance and the
 * item movement are asserted. No owner resolution / warmname is needed (the sign
 * is detected as admin before any account lookup).
 */
class AdminShopTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int QTY = 5;
    private static final int PRICE = 100;

    @Test
    void buyFromAdminShopDebitsBuyerAndYieldsItems() {
        String buyer = Usernames.fresh("Adb");

        try (MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            shop.setupSellShop("Admin Shop", QTY, PRICE, "dirt");
            int initialStock = Inv.chestCount(shop.chestPos(), "DIRT");

            shop.clickBuy(b, buyer);

            // Buyer paid; got the goods; the (chest-backed) admin shop's stock fell.
            Ledger.waitForPersonalBalance(b.getUuid(),
                    STARTING.subtract(BigDecimal.valueOf(PRICE)), TIMEOUT);
            Inv.waitForPlayerCount(buyer, "DIRT", QTY, TIMEOUT);
            Inv.waitForChestCount(shop.chestPos(), "DIRT", initialStock - QTY, TIMEOUT);
        }
    }

    @Test
    void sellToAdminShopCreditsClientAndFillsChest() {
        String client = Usernames.fresh("Ads");

        try (MockPlayer c = MockPlayer.connectLocal(client);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(c.getUuid(), STARTING, TIMEOUT);

            shop.setupBuyShop("Admin Shop", QTY, PRICE, "dirt");
            Inv.give(client, "DIRT", QTY);
            Inv.waitForPlayerCount(client, "DIRT", QTY, TIMEOUT);

            shop.clickSell(c, client);

            // Client got paid (server-side money, always available); items went to the chest.
            Ledger.waitForPersonalBalance(c.getUuid(),
                    STARTING.add(BigDecimal.valueOf(PRICE)), TIMEOUT);
            Inv.waitForChestCount(shop.chestPos(), "DIRT", QTY, TIMEOUT);
            Inv.waitForPlayerCount(client, "DIRT", 0, TIMEOUT);
        }
    }
}
