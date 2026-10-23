package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class MarketFirmRenameTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void renameAndReuseOfOldNamePreserveShopIdentityAndPaymentDestination() throws Exception {
        String original = Usernames.fresh("ShopF");
        String renamed = Usernames.fresh("ShopR");
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("ShopO"));
             var buyer = MockPlayer.connectLocal(Usernames.fresh("ShopB"));
             var otherOwner = MockPlayer.connectLocal(Usernames.fresh("ShopN"));
             var shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(buyer.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(otherOwner.getUuid(), TIMEOUT);
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business create " + original);
                owner.awaitMessageContaining("Successfully created", TIMEOUT);
            });
            int firmId = BusinessLedger.waitForFirm(original, TIMEOUT);
            int account = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
            Ops.run(owner.getUsername(), () -> assertThat(shop.createSellShop(owner.getUsername(),
                    "B:" + Integer.toString(account, 36).toUpperCase(), 2, 7, "TUFF")).contains("cancelled=false"));
            shop.clickBuy(buyer, buyer.getUsername());
            Market.waitForSaleCount(buyer.getUuid(), 1, TIMEOUT);
            Barrier.settle();
            var before = onlyShop(firmId);
            assertThat(before.path("ownerName").asText()).isEqualTo(original);
            assertThat(before.path("currentStock").asInt()).isEqualTo(22);
            String key = java.net.URLEncoder.encode(before.path("itemKey").asText(),
                    java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            String detailUrl = "/chestshop/items/" + key + "?days=30";
            var initialDetail = get(detailUrl);
            assertThat(initialDetail.path("cheapestShops").size()).isEqualTo(1);
            identity(initialDetail.path("cheapestShops").get(0), before, firmId, original);
            assertThat(initialDetail.path("tradeCount").asInt()).isEqualTo(1);
            long checkpoint = Ledger.transactionCheckpoint();
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business admin rename " + firmId + " " + renamed);
                owner.awaitMessageContaining("Renamed " + original + " to " + renamed, TIMEOUT);
            });
            Barrier.settle();
            assertThat(BusinessLedger.firmId(original)).isEmpty();
            var after = onlyShop(firmId);
            identity(after, before, firmId, renamed);
            assertThat(after.path("currentStock").asInt()).isEqualTo(22);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            // Reusing the released name must not redirect a sign bound to the original account.
            Ops.run(otherOwner.getUsername(), () -> {
                otherOwner.chatCommand("/business create " + original);
                otherOwner.awaitMessageContaining("Successfully created", TIMEOUT);
            });
            int replacement = BusinessLedger.waitForFirm(original, TIMEOUT);
            assertThat(replacement).isNotEqualTo(firmId);
            var empty = get("/chestshop/shops?firmId=" + replacement);
            assertThat(empty.path("totalItems").asInt()).isZero();
            assertThat(empty.path("items").size()).isZero();
            shop.clickBuy(buyer, buyer.getUsername());
            Market.waitForSaleCount(buyer.getUuid(), 2, TIMEOUT);
            Barrier.settle();
            var finalRow = onlyShop(firmId);
            identity(finalRow, before, firmId, renamed);
            assertThat(finalRow.path("currentStock").asInt()).isEqualTo(20);
            var sale = Market.latestSaleForCustomer(buyer.getUuid()).orElseThrow();
            assertThat(sale.shopFirmId()).isEqualTo(firmId);
            assertThat(sale.shopAccountType()).isEqualTo("BUSINESS");
            assertThat(sale.quantity()).isEqualTo(2);
            assertThat(sale.totalPrice()).isEqualByComparingTo("7");
            assertThat(BusinessLedger.firmDefaultAccountId(firmId)).hasValue(account);
            assertThat(BusinessLedger.firmDefaultBalance(firmId).orElseThrow()).isEqualByComparingTo("14");
            assertThat(BusinessLedger.firmDefaultBalance(replacement).orElseThrow()).isEqualByComparingTo("0");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(otherOwner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow()).isEqualByComparingTo("9986");
            assertThat(Inv.playerCount(buyer.getUsername(), "TUFF")).isEqualTo(4);
            assertThat(Inv.chestCount(shop.chestPos(), "TUFF")).isEqualTo(20);
            assertThat(Market.saleCountForCustomer(buyer.getUuid())).isEqualTo(2);
            long afterTrade = Ledger.transactionCheckpoint();
            // Reuse the warmed URL: changing days would avoid the cache we need to exercise.
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            JsonNode refreshed;
            do {
                refreshed = get(detailUrl);
                var offers = refreshed.path("cheapestShops");
                if (offers.size() == 1 && offers.get(0).path("ownerName").asText().equals(renamed)
                        && offers.get(0).path("currentStock").asInt() == 20
                        && refreshed.path("tradeCount").asInt() == 2) break;
                Thread.sleep(1500); // Stay below the anonymous HTTP rate limit.
            } while (System.nanoTime() < deadline);
            assertThat(refreshed.path("cheapestShops").size()).isEqualTo(1);
            identity(refreshed.path("cheapestShops").get(0), before, firmId, renamed);
            assertThat(refreshed.path("cheapestShops").get(0).path("currentStock").asInt()).isEqualTo(20);
            assertThat(refreshed.path("tradeCount").asInt()).isEqualTo(2);
            assertThat(refreshed.path("totalQuantity").asInt()).isEqualTo(4);
            assertThat(new java.math.BigDecimal(refreshed.path("totalVolume").asText())).isEqualByComparingTo("14");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(afterTrade);
        }
    }

    private static void identity(JsonNode row, JsonNode original, int firmId, String name) {
        assertThat(row.path("shopId")).isEqualTo(original.path("shopId"));
        assertThat(row.path("firmId").asInt()).isEqualTo(firmId);
        assertThat(row.path("ownerName").asText()).isEqualTo(name);
        assertThat(row.path("accountType").asText()).isEqualTo("BUSINESS");
        assertThat(row.path("ownerUuid").isNull()).isTrue();
        assertThat(row.path("buyPrice").asText()).isEqualTo("7.00");
        assertThat(row.path("batchQty").asInt()).isEqualTo(2);
    }

    private static JsonNode onlyShop(int firmId) {
        var response = get("/chestshop/shops?firmId=" + firmId);
        assertThat(response.path("totalItems").asInt()).isEqualTo(1);
        assertThat(response.path("items").size()).isEqualTo(1);
        return response.path("items").get(0);
    }

    private static JsonNode get(String path) {
        var response = RestApi.call("GET", path, null, null);
        assertThat(response.status()).as("%s: %s", path, response.body()).isEqualTo(200);
        return response.json();
    }
}
