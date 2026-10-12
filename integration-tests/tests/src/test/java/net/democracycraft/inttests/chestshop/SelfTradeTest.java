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
 * An owner interacting with their own shop must never mint or destroy money.
 * Whether the fork treats it as access (open the container, no trade) or a
 * self-transfer (net zero), the owner's balance is unchanged and no money is
 * created — that conservation invariant is the correctness property here.
 */
class SelfTradeTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int QTY = 5;
    private static final int PRICE = 100;

    @Test
    void ownerClickingOwnShopMintsNoMoney() {
        String owner = Usernames.fresh("Sto");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(o.getUuid(), STARTING, TIMEOUT);

            shop.setupSellShop(owner, QTY, PRICE, "dirt");
            int initialStock = Inv.chestCount(shop.chestPos(), "DIRT");
            RconClient.send("diag warmname " + owner);

            shop.clickBuy(o, owner);
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // Money is conserved no matter how the interaction is interpreted.
            assertThat(Ledger.personalBalance(o.getUuid()).orElseThrow())
                    .as("owner interacting with own shop must not change their balance")
                    .isEqualByComparingTo(STARTING);
            // And items can't appear from nowhere: whatever the owner gained left the chest.
            int ownerDirt = Inv.playerCount(owner, "DIRT");
            int chestDirt = Inv.chestCount(shop.chestPos(), "DIRT");
            assertThat(ownerDirt + chestDirt)
                    .as("no dirt created or destroyed by a self-interaction")
                    .isEqualTo(initialStock);
        }
    }
}
