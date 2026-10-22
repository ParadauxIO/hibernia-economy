package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Await;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Inv;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Real supplied Nexo runtime: native custom identity, stock and exact payment on purchase. */
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "IT_NEXO_PROFILE", matches = "true")
class RealNexoCustomItemTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int QTY = 4;
    private static final int PRICE = 250;

    @Test
    void buyResolvesAndDeliversANexoCustomItem() {
        String seller = Usernames.fresh("Nxs");
        String buyer = Usernames.fresh("Nxb");

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            // Create the shop for the Nexo item. The item line is the bare id "test_ruby"
            // (ChestShop's sign pattern rejects colons, so Nexo items sit on signs bare and
            // resolve via acceptBareIds) — creation runs ItemService.parse, which must resolve
            // it through the Nexo bridge to the registered custom item.
            String created = shop.createSellShopRaw(seller, seller, QTY, PRICE, "test_ruby");
            assertThat(created).as("shop for a Nexo item should create").contains("cancelled=false");

            // The base material alone must not satisfy a shop for a Nexo identity.
            RconClient.send(String.format("data merge block %d %d %d {Items:[{Slot:0b,id:\"minecraft:diamond\",count:12}]}",
                    shop.chestPos().x(), shop.chestPos().y(), shop.chestPos().z()));
            Inv.waitForChestCount(shop.chestPos(), "DIAMOND", QTY * 3, TIMEOUT);
            RconClient.send("diag warmname " + seller);
            int buyerInitialPostings = Ledger.postingsCountForAccount(Ledger.personalAccountId(b.getUuid()).orElseThrow());
            int sellerInitialPostings = Ledger.postingsCountForAccount(Ledger.personalAccountId(s.getUuid()).orElseThrow());
            shop.clickBuy(b, buyer);
            assertThat(Ledger.personalBalance(b.getUuid()).orElseThrow()).isEqualByComparingTo(STARTING);
            assertThat(Ledger.personalBalance(s.getUuid()).orElseThrow()).isEqualByComparingTo(STARTING);
            assertThat(Inv.chestCount(shop.chestPos(), "DIAMOND")).isEqualTo(QTY * 3);
            assertThat(Inv.playerCount(buyer, "DIAMOND")).isZero();
            assertThat(Ledger.postingsCountForAccount(Ledger.personalAccountId(b.getUuid()).orElseThrow())).isEqualTo(buyerInitialPostings);
            assertThat(Ledger.postingsCountForAccount(Ledger.personalAccountId(s.getUuid()).orElseThrow())).isEqualTo(sellerInitialPostings);

            // Replace the stock with the actual Nexo item via Nexo’s API.
            String stockResp = RconClient.send(String.format("diag nexostock world %d %d %d test_ruby %d",
                    shop.chestPos().x(), shop.chestPos().y(), shop.chestPos().z(), QTY * 3));
            assertThat(stockResp).as("Nexo should stock the chest with the custom item").contains("nexostock test_ruby");
            RconClient.send("diag warmname " + seller);

            // Baseline: the buyer holds none of the custom item.
            assertThat(RconClient.send("diag nexocount " + buyer + " test_ruby")).contains("nexocount test_ruby = 0");

            int buyerAccount = Ledger.personalAccountId(b.getUuid()).orElseThrow();
            int sellerAccount = Ledger.personalAccountId(s.getUuid()).orElseThrow();
            int buyerPostings = Ledger.postingsCountForAccount(buyerAccount);
            int sellerPostings = Ledger.postingsCountForAccount(sellerAccount);
            shop.clickBuy(b, buyer);

            // Money moved, and the buyer received the CUSTOM item (asserted by nexo:id).
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING.subtract(BigDecimal.valueOf(PRICE)), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(), STARTING.add(BigDecimal.valueOf(PRICE)), TIMEOUT);

            Await.until("buyer to receive " + QTY + " of the Nexo custom item",
                    () -> RconClient.send("diag nexocount " + buyer + " test_ruby"),
                    r -> r.contains("nexocount test_ruby = " + QTY), TIMEOUT);
            Inv.waitForChestCount(shop.chestPos(), "DIAMOND", QTY * 2, TIMEOUT);
            assertThat(Ledger.postingsCountForAccount(buyerAccount)).isEqualTo(buyerPostings + 1);
            assertThat(Ledger.postingsCountForAccount(sellerAccount)).isEqualTo(sellerPostings + 1);
        }
    }
}
