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
 * The flip side of the partial-fill tests: with {@code ALLOW_PARTIAL_TRANSACTIONS}
 * off, a sub-batch chest can no longer be drained pro-rata — a buy that can't be
 * filled in full is rejected outright (all-or-nothing).
 */
class PartialTransactionsDisabledTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int QTY = 5;
    private static final int PRICE = 100;

    @AfterEach
    void restoreDefault() {
        RconClient.send("diag config allowPartialTransactions true");
    }

    @Test
    void subBatchStockRejectedWhenPartialDisabled() {
        RconClient.send("diag config allowPartialTransactions false");
        String seller = Usernames.fresh("Nps");
        String buyer = Usernames.fresh("Npb");
        int available = QTY - 1;

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
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // All-or-nothing: nothing moved.
            assertThat(Ledger.personalBalance(b.getUuid()).orElseThrow()).isEqualByComparingTo(STARTING);
            assertThat(Ledger.personalBalance(s.getUuid()).orElseThrow()).isEqualByComparingTo(STARTING);
            assertThat(Inv.playerCount(buyer, "DIRT")).isZero();
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(available);
        }
    }
}
