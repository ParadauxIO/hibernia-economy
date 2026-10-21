package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChestShop's built-in shop protection (USE_BUILT_IN_PROTECTION, default on) — the
 * default guard on a live server, independent of WorldGuard/GriefPrevention (both
 * opt-in and default off). A non-owner can't break another player's shop sign or
 * chest, and the owner can. Real hopper-minecart transfers are covered separately
 * by HopperMinecartProtectionTest.
 *
 * <p>Breaks are driven through a real {@code BlockBreakEvent} (via {@code diag
 * break}) — the same event ChestShop's SignBreak/ChestBreak listeners (and WG/GP)
 * hook — so a {@code cancelled=true} result is the protection firing.
 */
class ShopProtectionTest {

    private static final int QTY = 5;
    private static final int PRICE = 100;

    private static String breakAt(String player, int x, int y, int z) {
        return RconClient.send("diag break " + player + " world " + x + " " + y + " " + z);
    }

    private static String signType(ChestShopFixture shop) {
        return RconClient.send("diag checksign world "
                + shop.signPos().x() + " " + shop.signPos().y() + " " + shop.signPos().z());
    }

    @Test
    void nonOwnerCannotBreakShopSign() {
        String owner = Usernames.fresh("Pno");
        String breaker = Usernames.fresh("Pnb");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer br = MockPlayer.connectLocal(breaker);
             ChestShopFixture shop = ChestShopFixture.create()) {

            shop.setupSellShop(owner, QTY, PRICE, "dirt");
            RconClient.send("diag warmname " + owner);

            String result = breakAt(breaker, shop.signPos().x(), shop.signPos().y(), shop.signPos().z());
            assertThat(result).as("non-owner sign break must be blocked").contains("cancelled=true");
            assertThat(signType(shop)).as("sign survives a blocked break").doesNotContain("type=AIR");
        }
    }

    @Test
    void ownerCanBreakOwnShopSign() {
        String owner = Usernames.fresh("Poo");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             ChestShopFixture shop = ChestShopFixture.create()) {

            shop.setupSellShop(owner, QTY, PRICE, "dirt");
            RconClient.send("diag warmname " + owner);

            String result = breakAt(owner, shop.signPos().x(), shop.signPos().y(), shop.signPos().z());
            assertThat(result).as("owner may break their own shop").contains("cancelled=false");
            assertThat(signType(shop)).as("sign is gone after owner break").contains("type=AIR");
        }
    }

    @Test
    void nonOwnerCannotBreakShopChest() {
        String owner = Usernames.fresh("Pco");
        String breaker = Usernames.fresh("Pcb");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer br = MockPlayer.connectLocal(breaker);
             ChestShopFixture shop = ChestShopFixture.create()) {

            shop.setupSellShop(owner, QTY, PRICE, "dirt");
            RconClient.send("diag warmname " + owner);

            String result = breakAt(breaker, shop.chestPos().x(), shop.chestPos().y(), shop.chestPos().z());
            assertThat(result).as("non-owner chest break must be blocked").contains("cancelled=true");
        }
    }
}
