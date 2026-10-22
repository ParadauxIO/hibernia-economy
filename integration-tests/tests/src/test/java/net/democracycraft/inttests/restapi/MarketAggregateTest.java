package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class MarketAggregateTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    @Test
    void realTradesPopulateItemTotalsPriceSeriesCheapestShopsAndGlobalStats() throws Exception {
        String material = "RAW_GOLD_BLOCK";
        String firm = Usernames.fresh("AggF");
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("AggO"));
             var buyer = MockPlayer.connectLocal(Usernames.fresh("AggB"));
             var first = ChestShopFixture.create(); var second = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(buyer.getUuid(), TIMEOUT);
            assertThat(sql("SELECT COUNT(*) FROM chestshop_sale WHERE material = 'RAW_GOLD_BLOCK'")).isEqualTo("0");
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business create " + firm);
                owner.awaitMessageContaining("Successfully created", TIMEOUT);
            });
            int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int account = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
            String signOwner = "B:" + Integer.toString(account, 36).toUpperCase();
            Ops.run(owner.getUsername(), () -> {
                assertThat(first.createSellShop(owner.getUsername(), signOwner, 2, 7, material)).contains("cancelled=false");
                assertThat(second.createSellShop(owner.getUsername(), signOwner, 3, 11, material)).contains("cancelled=false");
            });
            var a = Market.waitForShopAt("world", first.signPos().x(), first.signPos().y(), first.signPos().z(), TIMEOUT);
            var b = Market.waitForShopAt("world", second.signPos().x(), second.signPos().y(), second.signPos().z(), TIMEOUT);
            first.clickBuy(buyer, buyer.getUsername());
            second.clickBuy(buyer, buyer.getUsername());
            Market.waitForSaleCount(buyer.getUuid(), 2, TIMEOUT);
            Barrier.settle();
            String encoded = java.net.URLEncoder.encode(a.itemKey(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            JsonNode detail = get("/chestshop/items/" + encoded + "?days=30");
            assertThat(detail.path("itemKey").asText()).isEqualTo(a.itemKey());
            assertThat(detail.path("material").asText()).isEqualTo(material);
            assertThat(detail.path("itemCustom").asBoolean()).isFalse();
            assertThat(detail.path("windowDays").asInt()).isEqualTo(30);
            totals(detail, 2, 5, "18");
            assertThat(new BigDecimal(detail.path("avgUnitPrice").asText())).isEqualByComparingTo("3.6");
            assertThat(detail.path("activeShopCount").asInt()).isEqualTo(2);
            var shops = detail.path("cheapestShops");
            assertThat(shops.size()).isEqualTo(2);
            assertThat(shops.get(0).path("shopId").asLong()).isEqualTo(a.shopId());
            assertThat(shops.get(1).path("shopId").asLong()).isEqualTo(b.shopId());
            assertThat(shops.get(0).path("currentStock").asInt()).isEqualTo(22);
            assertThat(shops.get(1).path("currentStock").asInt()).isEqualTo(33);
            // Group expected dates from the actual recorded trades, not the client's timezone.
            var days = detail.path("priceByDay");
            assertThat(days.size()).isEqualTo(1);
            assertThat(days.get(0).path("day").asText()).isEqualTo(sql("SELECT DATE_FORMAT(MIN(occurred_at), '%Y-%m-%d') FROM chestshop_sale WHERE material = 'RAW_GOLD_BLOCK'"));
            assertThat(days.get(0).path("sales").asInt()).isEqualTo(2);
            assertThat(days.get(0).path("totalQuantity").asInt()).isEqualTo(5);
            assertThat(new BigDecimal(days.get(0).path("totalVolume").asText())).isEqualByComparingTo("18");
            assertThat(new BigDecimal(days.get(0).path("avgUnitPrice").asText())).isEqualByComparingTo("3.6");
            var items = get("/chestshop/items?search=RAW_GOLD_BLOCK");
            assertThat(items.path("totalItems").asInt()).isEqualTo(1);
            assertThat(items.path("items").size()).isEqualTo(1);
            var item = items.path("items").get(0);
            assertThat(item.path("itemKey").asText()).isEqualTo(a.itemKey());
            assertThat(item.path("material").asText()).isEqualTo(material);
            totals(item, 2, 5, "18");
            long sales = Long.parseLong(sql("SELECT COUNT(*) FROM chestshop_sale"));
            BigDecimal volume = new BigDecimal(sql("SELECT COALESCE(SUM(total_price),0) FROM chestshop_sale"));
            long distinct = Long.parseLong(sql("SELECT COUNT(DISTINCT item_key) FROM chestshop_sale"));
            long active = Long.parseLong(sql("SELECT COUNT(*) FROM chestshop_shop WHERE active = 1"));
            // Public aggregates may retain a prior test's cache for up to 60 seconds.
            JsonNode stats = get("/chestshop/stats");
            long deadline = System.nanoTime() + Duration.ofSeconds(75).toNanos();
            while (stats.path("totalSales").asLong() != sales && System.nanoTime() < deadline) {
                Thread.sleep(1000); // Respect the 120/min anonymous route budget while awaiting cache expiry.
                stats = get("/chestshop/stats");
            }
            assertThat(stats.path("totalSales").asLong()).isEqualTo(sales);
            assertThat(new BigDecimal(stats.path("totalVolume").asText())).isEqualByComparingTo(volume);
            assertThat(stats.path("distinctItems").asLong()).isEqualTo(distinct);
            assertThat(stats.path("activeShops").asLong()).isEqualTo(active);
            assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow()).isEqualByComparingTo("9982");
            assertThat(BusinessLedger.firmDefaultBalance(firmId).orElseThrow()).isEqualByComparingTo("18");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Inv.playerCount(buyer.getUsername(), material)).isEqualTo(5);
            assertThat(Market.saleCountForCustomer(buyer.getUuid())).isEqualTo(2);
        }
    }
    private static void totals(JsonNode node, int trades, int quantity, String volume) {
        assertThat(node.path("tradeCount").asInt()).isEqualTo(trades);
        assertThat(node.path("totalQuantity").asInt()).isEqualTo(quantity);
        assertThat(new BigDecimal(node.path("totalVolume").asText())).isEqualByComparingTo(volume);
    }
    private static JsonNode get(String path) {
        var r = RestApi.call("GET", path, null, null);
        assertThat(r.status()).as("%s: %s", path, r.body()).isEqualTo(200);
        return r.json();
    }
    private static String sql(String query) throws Exception {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var s = c.createStatement(); var r = s.executeQuery(query)) {
            assertThat(r.next()).isTrue();
            return r.getString(1);
        }
    }
}
