package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.Duration;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the supplied Utilities listener through native shop transactions. */
@EnabledIfEnvironmentVariable(named = "IT_PROVIDERS_PROFILE", matches = "true")
class RealUtilitiesPermissionTest {
    @Test
    void startupItemPermissionDeniesWithoutSettlementAndAllowsAfterGrant() throws Exception {
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("UtOwn"));
             var customer = MockPlayer.connectLocal(Usernames.fresh("UtCli"));
             var shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), new BigDecimal("10000"), Duration.ofSeconds(10));
            Ledger.waitForPersonalBalance(customer.getUuid(), new BigDecimal("10000"), Duration.ofSeconds(10));
            assertThat(shop.createSellShopRaw(owner.getUsername(), owner.getUsername(), 4, 250, "lockedruby"))
                    .contains("cancelled=false");
            assertThat(RconClient.send(String.format("diag nexostock world %d %d %d test_restricted 8",
                    shop.chestPos().x(), shop.chestPos().y(), shop.chestPos().z()))).contains("nexostock test_restricted");
            RconClient.send("diag warmname " + owner.getUsername());
            int sellerAccount = Ledger.personalAccountId(owner.getUuid()).orElseThrow();
            int buyerAccount = Ledger.personalAccountId(customer.getUuid()).orElseThrow();
            int sellerPostings = Ledger.postingsCountForAccount(sellerAccount);
            int buyerPostings = Ledger.postingsCountForAccount(buyerAccount);
            RconClient.send("lp user " + customer.getUsername() + " permission set shop.buy.test_restricted false");
            shop.clickBuy(customer, customer.getUsername());
            assertThat(Inv.chestCount(shop.chestPos(), "DIAMOND")).isEqualTo(8);
            assertThat(Inv.playerCount(customer.getUsername(), "DIAMOND")).isZero();
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(customer.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.postingsCountForAccount(sellerAccount)).isEqualTo(sellerPostings);
            assertThat(Ledger.postingsCountForAccount(buyerAccount)).isEqualTo(buyerPostings);
            RconClient.send("lp user " + customer.getUsername() + " permission set shop.buy.test_restricted true");
            shop.clickBuy(customer, customer.getUsername());
            assertThat(RconClient.send("diag nexocount " + customer.getUsername() + " test_restricted"))
                    .contains("nexocount test_restricted = 4");
            assertThat(Inv.chestCount(shop.chestPos(), "DIAMOND")).isEqualTo(4);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10250");
            assertThat(Ledger.personalBalance(customer.getUuid()).orElseThrow()).isEqualByComparingTo("9750");
            assertThat(Ledger.postingsCountForAccount(sellerAccount)).isEqualTo(sellerPostings + 1);
            assertThat(Ledger.postingsCountForAccount(buyerAccount)).isEqualTo(buyerPostings + 1);
        }
    }
}
