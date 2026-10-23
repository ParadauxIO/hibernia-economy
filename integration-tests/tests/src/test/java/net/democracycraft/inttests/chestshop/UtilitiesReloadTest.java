package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the supplied Utilities listener through native shop transactions. */
@EnabledIfEnvironmentVariable(named = "IT_PROVIDERS_PROFILE", matches = "true")
class UtilitiesReloadTest {
    @org.junit.jupiter.api.Disabled("PAR-496: supplied Utilities 3.0.0 reload omits ShopPermissionManager.reload; preserved red run 20261022T095004Z-3048952")
    @Test
    void reloadedItemPermissionDeniesWithoutSettlementAndAllowsAfterGrant() throws Exception {
        Path config = Path.of(System.getenv("IT_SERVER_DIR"), "plugins", "Utilities", "config.yml");
        String original = Files.readString(config);
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("UtOwn"));
             var customer = MockPlayer.connectLocal(Usernames.fresh("UtCli"));
             var shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), new BigDecimal("10000"), Duration.ofSeconds(10));
            Ledger.waitForPersonalBalance(customer.getUuid(), new BigDecimal("10000"), Duration.ofSeconds(10));
            assertThat(shop.createSellShopRaw(owner.getUsername(), owner.getUsername(), 4, 250, "ruby"))
                    .contains("cancelled=false");
            assertThat(RconClient.send(String.format("diag nexostock world %d %d %d test_ruby 8",
                    shop.chestPos().x(), shop.chestPos().y(), shop.chestPos().z()))).contains("nexostock test_ruby");
            RconClient.send("diag warmname " + owner.getUsername());
            int sellerAccount = Ledger.personalAccountId(owner.getUuid()).orElseThrow();
            int buyerAccount = Ledger.personalAccountId(customer.getUuid()).orElseThrow();
            int sellerPostings = Ledger.postingsCountForAccount(sellerAccount);
            int buyerPostings = Ledger.postingsCountForAccount(buyerAccount);
            Files.writeString(config, original.replace("shopPermissions: { enabled: true, items: [test_restricted] }",
                    "shopPermissions: { enabled: true, items: [test_ruby] }"));
            RconClient.send("utilities reload");
            RconClient.send("lp user " + customer.getUsername() + " permission set shop.buy.test_ruby false");
            shop.clickBuy(customer, customer.getUsername());
            assertThat(Inv.chestCount(shop.chestPos(), "DIAMOND")).isEqualTo(8);
            assertThat(Inv.playerCount(customer.getUsername(), "DIAMOND")).isZero();
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(customer.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.postingsCountForAccount(sellerAccount)).isEqualTo(sellerPostings);
            assertThat(Ledger.postingsCountForAccount(buyerAccount)).isEqualTo(buyerPostings);
            RconClient.send("lp user " + customer.getUsername() + " permission set shop.buy.test_ruby true");
            shop.clickBuy(customer, customer.getUsername());
            assertThat(RconClient.send("diag nexocount " + customer.getUsername() + " test_ruby"))
                    .contains("nexocount test_ruby = 4");
            assertThat(Inv.chestCount(shop.chestPos(), "DIAMOND")).isEqualTo(4);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10250");
            assertThat(Ledger.personalBalance(customer.getUuid()).orElseThrow()).isEqualByComparingTo("9750");
            assertThat(Ledger.postingsCountForAccount(sellerAccount)).isEqualTo(sellerPostings + 1);
            assertThat(Ledger.postingsCountForAccount(buyerAccount)).isEqualTo(buyerPostings + 1);
        } finally {
            Files.writeString(config, original);
            RconClient.send("utilities reload");
        }
    }
}
