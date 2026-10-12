package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Inv;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code FORCE_UNLIMITED_ADMIN_SHOP}: an admin shop sells even from an empty chest.
 * With the flag on, buying from an "Admin Shop" whose chest holds nothing still
 * hands the buyer goods and debits them — the stock check is bypassed for admin
 * shops.
 */
class ForceUnlimitedAdminShopTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int QTY = 5;
    private static final int PRICE = 100;

    @AfterEach
    void restoreDefault() {
        RconClient.send("diag config forceUnlimitedAdminShop false");
    }

    @Test
    void unlimitedAdminShopSellsFromAnEmptyChest() {
        RconClient.send("diag config forceUnlimitedAdminShop true");
        String buyer = Usernames.fresh("Fub");

        try (MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            shop.setupSellShop("Admin Shop", QTY, PRICE, "dirt");
            // Empty the chest in place (setblock of the same block type is a no-op).
            RconClient.send(String.format("data merge block %d %d %d {Items:[]}",
                    shop.chestPos().x(), shop.chestPos().y(), shop.chestPos().z()));
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isZero();

            shop.clickBuy(b, buyer);

            // Sold from nothing: buyer gets goods and is charged.
            Inv.waitForPlayerCount(buyer, "DIRT", QTY, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(),
                    STARTING.subtract(BigDecimal.valueOf(PRICE)), TIMEOUT);
        }
    }
}
