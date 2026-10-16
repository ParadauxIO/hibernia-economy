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
 * Sell-side rejections (the buy-side lives in {@link FailureModesTest}). A
 * rejected sell must move no items and no money: neither when the shop's chest
 * has no room, nor when the owner can't afford the payout. Asserted against the
 * ledger + container state.
 */
class SellRejectionsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int QTY = 5;
    private static final int PRICE = 50;

    @Test
    void sellRejectedWhenChestFull() {
        String owner = Usernames.fresh("Sfo");
        String client = Usernames.fresh("Sfc");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer c = MockPlayer.connectLocal(client);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(o.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(c.getUuid(), STARTING, TIMEOUT);

            shop.setupBuyShop(owner, QTY, PRICE, "dirt");
            shop.fillChestWith("cobblestone"); // no slot can accept dirt
            Inv.give(client, "DIRT", QTY);
            Inv.waitForPlayerCount(client, "DIRT", QTY, TIMEOUT);

            BigDecimal ownerBefore = Ledger.personalBalance(o.getUuid()).orElseThrow();

            shop.clickSell(c, client);
            // The click returned only once the server had processed it (ChestShopFixture#click), so the rejection is final.

            // Rejected: client keeps items + money, owner unchanged, no dirt in the chest.
            assertThat(Ledger.personalBalance(c.getUuid()).orElseThrow()).isEqualByComparingTo(STARTING);
            assertThat(Ledger.personalBalance(o.getUuid()).orElseThrow()).isEqualByComparingTo(ownerBefore);
            assertThat(Inv.playerCount(client, "DIRT")).isEqualTo(QTY);
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isZero();
        }
    }

    @Test
    void sellRejectedWhenOwnerCannotAfford() {
        String owner = Usernames.fresh("Sbo");
        String client = Usernames.fresh("Sbc");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer c = MockPlayer.connectLocal(client);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(o.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(c.getUuid(), STARTING, TIMEOUT);

            shop.setupBuyShop(owner, QTY, PRICE, "dirt");
            Inv.give(client, "DIRT", QTY);
            Inv.waitForPlayerCount(client, "DIRT", QTY, TIMEOUT);

            // Drain the owner so they can't pay for the goods.
            RconClient.send("eco take " + owner + " 10000");
            Ledger.waitForPersonalBalance(o.getUuid(), BigDecimal.ZERO, TIMEOUT);

            shop.clickSell(c, client);
            // The click returned only once the server had processed it (ChestShopFixture#click), so the rejection is final.

            // Rejected: client keeps items + money, owner still broke, chest still empty.
            assertThat(Ledger.personalBalance(c.getUuid()).orElseThrow()).isEqualByComparingTo(STARTING);
            assertThat(Ledger.personalBalance(o.getUuid()).orElseThrow()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(Inv.playerCount(client, "DIRT")).isEqualTo(QTY);
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isZero();
        }
    }
}
