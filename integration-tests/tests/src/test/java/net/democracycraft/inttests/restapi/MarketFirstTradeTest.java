package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class MarketFirstTradeTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void neverTradedLiveItemBecomesTradedOnTheSameCachedUrls() throws Exception {
        String material = "WAXED_COPPER_BLOCK";
        String firm = Usernames.fresh("First");
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("FstO"));
             var customer = MockPlayer.connectLocal(Usernames.fresh("FstC"));
             var shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(customer.getUuid(), TIMEOUT);
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business create " + firm);
                owner.awaitMessageContaining("Successfully created", TIMEOUT);
            });
            int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int account = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
            Ops.run(owner.getUsername(), () -> assertThat(shop.createSellShop(owner.getUsername(),
                    "B:" + Integer.toString(account, 36).toUpperCase(), 2, 9, material)).contains("cancelled=false"));
            var pos = shop.signPos();
            var registered = Market.waitForShopAt("world", pos.x(), pos.y(), pos.z(), TIMEOUT);
            String key = java.net.URLEncoder.encode(registered.itemKey(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            String detailUrl = "/chestshop/items/" + key + "?days=30";
            String directoryUrl = "/chestshop/items?search=" + material;
            var before = get(detailUrl);
            assertThat(before.path("itemKey").asText()).isEqualTo(registered.itemKey());
            totals(before, 0, 0, "0");
            assertThat(new BigDecimal(before.path("avgUnitPrice").asText())).isEqualByComparingTo("0");
            assertThat(before.path("priceByDay").size()).isZero();
            assertThat(before.path("activeShopCount").asInt()).isEqualTo(1);
            assertThat(before.path("cheapestShops").size()).isEqualTo(1);
            assertThat(before.path("cheapestShops").get(0).path("shopId").asLong()).isEqualTo(registered.shopId());
            assertThat(before.path("cheapestShops").get(0).path("currentStock").asInt()).isEqualTo(24);
            var empty = get(directoryUrl);
            assertThat(empty.path("totalItems").asInt()).isZero();
            assertThat(empty.path("items").size()).isZero();

            shop.clickBuy(customer, customer.getUsername());
            Market.waitForSaleCount(customer.getUuid(), 1, TIMEOUT);
            Barrier.settle();
            long checkpoint = Ledger.transactionCheckpoint();
            // Warm both cache entries before the sale, then use identical URLs until refreshed.
            // Immediate invalidation is also valid; do not require a stale response.
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            JsonNode detail;
            JsonNode directory;
            do {
                detail = get(detailUrl);
                directory = get(directoryUrl);
                if (detail.path("tradeCount").asInt() == 1 && directory.path("totalItems").asInt() == 1) break;
                Thread.sleep(1500); // Bound HTTP polling below the anonymous route budget.
            } while (System.nanoTime() < deadline);
            totals(detail, 1, 2, "9");
            assertThat(detail.path("material").asText()).isEqualTo(material);
            assertThat(new BigDecimal(detail.path("avgUnitPrice").asText())).isEqualByComparingTo("4.5");
            assertThat(detail.path("cheapestShops").size()).isEqualTo(1);
            assertThat(detail.path("cheapestShops").get(0).path("currentStock").asInt()).isEqualTo(22);
            assertThat(detail.path("priceByDay").size()).isEqualTo(1);
            assertThat(directory.path("totalItems").asInt()).isEqualTo(1);
            assertThat(directory.path("items").size()).isEqualTo(1);
            totals(directory.path("items").get(0), 1, 2, "9");
            assertThat(directory.path("items").get(0).path("itemKey").asText()).isEqualTo(registered.itemKey());
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            assertThat(Ledger.personalBalance(customer.getUuid()).orElseThrow()).isEqualByComparingTo("9991");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(BusinessLedger.firmDefaultBalance(firmId).orElseThrow()).isEqualByComparingTo("9");
            assertThat(Inv.playerCount(customer.getUsername(), material)).isEqualTo(2);
            assertThat(Inv.chestCount(shop.chestPos(), material)).isEqualTo(22);
            assertThat(Market.saleCountForCustomer(customer.getUuid())).isEqualTo(1);
        }
    }

    private static void totals(JsonNode node, int count, int quantity, String volume) {
        assertThat(node.path("tradeCount").asInt()).isEqualTo(count);
        assertThat(node.path("totalQuantity").asInt()).isEqualTo(quantity);
        assertThat(new BigDecimal(node.path("totalVolume").asText())).isEqualByComparingTo(volume);
    }

    private static JsonNode get(String path) {
        var response = RestApi.call("GET", path, null, null);
        assertThat(response.status()).as("%s: %s", path, response.body()).isEqualTo(200);
        return response.json();
    }
}
