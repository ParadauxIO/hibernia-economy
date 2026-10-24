package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Await;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.LedgerFaults;
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
    void sellingRequiresNativeIdentityAndRestoresGoodsWhenPaymentFails() throws Exception {
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("NxOwn"));
             var customer = MockPlayer.connectLocal(Usernames.fresh("NxCli"));
             var supply = ChestShopFixture.create();
             var demand = ChestShopFixture.create()) {
            String seller = owner.getUsername();
            String buyer = customer.getUsername();
            Ledger.waitForPersonalBalance(owner.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(customer.getUuid(), STARTING, TIMEOUT);
            assertThat(supply.createSellShopRaw(seller, seller, QTY, PRICE, "test_ruby")).contains("cancelled=false");
            assertThat(demand.createBuyShopRaw(seller, seller, QTY, 100, "test_ruby")).contains("cancelled=false");
            RconClient.send("diag warmname " + seller);
            int ownerAccount = Ledger.personalAccountId(owner.getUuid()).orElseThrow();
            int customerAccount = Ledger.personalAccountId(customer.getUuid()).orElseThrow();
            int ownerPostings = Ledger.postingsCountForAccount(ownerAccount);
            int customerPostings = Ledger.postingsCountForAccount(customerAccount);
            long checkpoint = Ledger.transactionCheckpoint();

            Inv.give(buyer, "DIAMOND", QTY);
            Inv.waitForPlayerCount(buyer, "DIAMOND", QTY, TIMEOUT);
            demand.clickSell(customer, buyer);
            assertThat(Inv.playerCount(buyer, "DIAMOND")).isEqualTo(QTY);
            assertThat(Inv.chestCount(demand.chestPos(), "DIAMOND")).isZero();
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo(STARTING);
            assertThat(Ledger.personalBalance(customer.getUuid()).orElseThrow()).isEqualByComparingTo(STARTING);
            assertThat(Ledger.postingsCountForAccount(ownerAccount)).isEqualTo(ownerPostings);
            assertThat(Ledger.postingsCountForAccount(customerAccount)).isEqualTo(customerPostings);

            // Obtain native goods through a real purchase, preserving ordinary diamonds too.
            assertThat(RconClient.send(String.format("diag nexostock world %d %d %d test_ruby %d",
                    supply.chestPos().x(), supply.chestPos().y(), supply.chestPos().z(), QTY)))
                    .contains("nexostock test_ruby");
            supply.clickBuy(customer, buyer);
            assertThat(RconClient.send("diag nexocount " + buyer + " test_ruby")).contains("nexocount test_ruby = 4");
            assertThat(Inv.chestCount(supply.chestPos(), "DIAMOND")).isZero();
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10250");
            assertThat(Ledger.personalBalance(customer.getUuid()).orElseThrow()).isEqualByComparingTo("9750");
            assertThat(Ledger.postingsCountForAccount(ownerAccount)).isEqualTo(ownerPostings + 1);
            assertThat(Ledger.postingsCountForAccount(customerAccount)).isEqualTo(customerPostings + 1);

            try (var fault = LedgerFaults.rejectDebits(ownerAccount)) {
                demand.clickSell(customer, buyer);
                assertThat(RconClient.send("diag nexocount " + buyer + " test_ruby")).contains("nexocount test_ruby = 4");
                assertThat(Inv.playerCount(buyer, "DIAMOND")).isEqualTo(QTY * 2);
                assertThat(Inv.chestCount(demand.chestPos(), "DIAMOND")).isZero();
                assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10250");
                assertThat(Ledger.personalBalance(customer.getUuid()).orElseThrow()).isEqualByComparingTo("9750");
                assertThat(Ledger.postingsCountForAccount(ownerAccount)).isEqualTo(ownerPostings + 1);
                assertThat(Ledger.postingsCountForAccount(customerAccount)).isEqualTo(customerPostings + 1);
            }
            demand.clickSell(customer, buyer);
            assertThat(RconClient.send("diag nexocount " + buyer + " test_ruby")).contains("nexocount test_ruby = 0");
            assertThat(Inv.playerCount(buyer, "DIAMOND")).isEqualTo(QTY);
            assertThat(Inv.chestCount(demand.chestPos(), "DIAMOND")).isEqualTo(QTY);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10150");
            assertThat(Ledger.personalBalance(customer.getUuid()).orElseThrow()).isEqualByComparingTo("9850");
            assertThat(Ledger.postingsCountForAccount(ownerAccount)).isEqualTo(ownerPostings + 2);
            assertThat(Ledger.postingsCountForAccount(customerAccount)).isEqualTo(customerPostings + 2);
            pairedPayment(customer.getUuid(), checkpoint, customerAccount, ownerAccount, "250");
            pairedPayment(owner.getUuid(), checkpoint, ownerAccount, customerAccount, "100");
        }
    }

    private static void pairedPayment(java.util.UUID initiator, long checkpoint, int debit, int credit, String amount) {
        var postings = Ledger.initiatedPostingsAfter(initiator, checkpoint);
        assertThat(postings).hasSize(2);
        assertThat(postings.get(0).transactionId()).isEqualTo(postings.get(1).transactionId());
        assertThat(Ledger.initiatedTransactionCountAfter(initiator, checkpoint)).isEqualTo(1);
        assertThat(postings.stream().filter(p -> p.accountId() == debit).toList()).singleElement()
                .satisfies(p -> assertThat(p.amount()).isEqualByComparingTo(new BigDecimal(amount).negate()));
        assertThat(postings.stream().filter(p -> p.accountId() == credit).toList()).singleElement()
                .satisfies(p -> assertThat(p.amount()).isEqualByComparingTo(amount));
    }

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
