package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Native Nexo item metadata acceptance; does not certify graphical rendering. */
@EnabledIfEnvironmentVariable(named = "IT_NEXO_PROFILE", matches = "true")
class RealNexoDyeTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @CsvSource({"red,B02E26", "12ab34,12AB34"})
    void dyeIdentitySurvivesPurchaseAndSaleAndRejectsAnotherColor(String tag, String color) throws Exception {
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("DyOw"));
             var customer = MockPlayer.connectLocal(Usernames.fresh("DyCl"));
             var supply = ChestShopFixture.create();
             var demand = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), new BigDecimal("10000"), TIMEOUT);
            Ledger.waitForPersonalBalance(customer.getUuid(), new BigDecimal("10000"), TIMEOUT);
            String code = "test_cap#" + tag;
            assertThat(supply.createSellShopRaw(owner.getUsername(), owner.getUsername(), 1, 25, code))
                    .contains("cancelled=false");
            assertThat(demand.createBuyShopRaw(owner.getUsername(), owner.getUsername(), 1, 10, code))
                    .contains("cancelled=false");
            RconClient.send("diag warmname " + owner.getUsername());
            int ownerAccount = Ledger.personalAccountId(owner.getUuid()).orElseThrow();
            int customerAccount = Ledger.personalAccountId(customer.getUuid()).orElseThrow();
            int ownerPostings = Ledger.postingsCountForAccount(ownerAccount);
            int customerPostings = Ledger.postingsCountForAccount(customerAccount);
            long checkpoint = Ledger.transactionCheckpoint();
            stock(supply, "0000FF");
            supply.clickBuy(customer, customer.getUsername());
            assertThat(Inv.chestCount(supply.chestPos(), "LEATHER_HELMET")).isEqualTo(1);
            assertThat(Inv.playerCount(customer.getUsername(), "LEATHER_HELMET")).isZero();
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(customer.getUuid()).orElseThrow()).isEqualByComparingTo("10000");

            stock(supply, color);
            supply.clickBuy(customer, customer.getUsername());
            assertThat(RconClient.send("diag nexocount " + customer.getUsername() + " test_cap " + color))
                    .contains("nexocount test_cap = 1");
            assertThat(RconClient.send("diag nexocount " + customer.getUsername() + " test_cap 0000FF"))
                    .contains("nexocount test_cap = 0");
            assertThat(Inv.chestCount(supply.chestPos(), "LEATHER_HELMET")).isZero();
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10025");
            assertThat(Ledger.personalBalance(customer.getUuid()).orElseThrow()).isEqualByComparingTo("9975");
            demand.clickSell(customer, customer.getUsername());
            assertThat(Inv.playerCount(customer.getUsername(), "LEATHER_HELMET")).isZero();
            assertThat(Inv.chestCount(demand.chestPos(), "LEATHER_HELMET")).isEqualTo(1);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10015");
            assertThat(Ledger.personalBalance(customer.getUuid()).orElseThrow()).isEqualByComparingTo("9985");
            assertThat(Ledger.postingsCountForAccount(ownerAccount)).isEqualTo(ownerPostings + 2);
            assertThat(Ledger.postingsCountForAccount(customerAccount)).isEqualTo(customerPostings + 2);
            payment(customer.getUuid(), checkpoint, customerAccount, ownerAccount, "25");
            payment(owner.getUuid(), checkpoint, ownerAccount, customerAccount, "10");
        }
    }

    private static void stock(ChestShopFixture shop, String color) {
        var pos = shop.chestPos();
        assertThat(RconClient.send("diag nexostock world " + pos.x() + " " + pos.y() + " " + pos.z()
                + " test_cap 1 " + color)).contains("nexostock test_cap");
    }

    private static void payment(UUID actor, long checkpoint, int debit, int credit, String amount) {
        var postings = Ledger.initiatedPostingsAfter(actor, checkpoint);
        assertThat(postings).hasSize(2);
        assertThat(Ledger.initiatedTransactionCountAfter(actor, checkpoint)).isEqualTo(1);
        assertThat(postings).extracting(Ledger.Posting::transactionId).containsOnly(postings.getFirst().transactionId());
        assertThat(postings.stream().filter(p -> p.accountId() == debit).toList()).singleElement()
                .satisfies(p -> assertThat(p.amount()).isEqualByComparingTo(new BigDecimal(amount).negate()));
        assertThat(postings.stream().filter(p -> p.accountId() == credit).toList()).singleElement()
                .satisfies(p -> assertThat(p.amount()).isEqualByComparingTo(amount));
    }
}
