package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class MarketWindowTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    @Test
    void detailWindowsExcludeOldSalesWhileDirectoryRetainsLifetimeTotals() throws Exception {
        String material = "RAW_IRON_BLOCK", firm = Usernames.fresh("WinF");
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("WinO"));
             var buyer = MockPlayer.connectLocal(Usernames.fresh("WinB"));
             var older = ChestShopFixture.create(); var recent = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(buyer.getUuid(), TIMEOUT);
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business create " + firm);
                owner.awaitMessageContaining("Successfully created", TIMEOUT);
            });
            int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int account = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
            String sign = "B:" + Integer.toString(account, 36).toUpperCase();
            Ops.run(owner.getUsername(), () -> {
                assertThat(older.createSellShop(owner.getUsername(), sign, 2, 7, material)).contains("cancelled=false");
                assertThat(recent.createSellShop(owner.getUsername(), sign, 3, 11, material)).contains("cancelled=false");
            });
            var registered = Market.waitForShopAt("world", older.signPos().x(), older.signPos().y(), older.signPos().z(), TIMEOUT);
            older.clickBuy(buyer, buyer.getUsername());
            var firstSale = Market.waitForSaleForCustomer(buyer.getUuid(), TIMEOUT);
            recent.clickBuy(buyer, buyer.getUsername());
            Market.waitForSaleCount(buyer.getUuid(), 2, TIMEOUT);
            var secondSale = Market.latestSaleForCustomer(buyer.getUuid()).orElseThrow();
            assertThat(secondSale.saleId()).isNotEqualTo(firstSale.saleId());
            long checkpoint = Ledger.transactionCheckpoint();
            String oldDay, newDay;
            try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD)) {
                // Only the recorded sale's timestamp changes; trades, inventory and ledger remain real.
                try (var q = c.prepareStatement("UPDATE chestshop_sale SET occurred_at = UTC_DATE() - INTERVAL 40 DAY + INTERVAL 12 HOUR WHERE sale_id = ?")) {
                    q.setLong(1, firstSale.saleId());
                    assertThat(q.executeUpdate()).isEqualTo(1);
                }
                oldDay = day(c, firstSale.saleId());
                newDay = day(c, secondSale.saleId());
            }
            assertThat(oldDay).isLessThan(newDay);
            String itemPath = "/chestshop/items/" + java.net.URLEncoder.encode(registered.itemKey(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            var shortWindow = get(itemPath + "?days=30");
            totals(shortWindow, 1, 3, "11");
            assertThat(shortWindow.path("windowDays").asInt()).isEqualTo(30);
            assertThat(new BigDecimal(shortWindow.path("avgUnitPrice").asText())).isEqualByComparingTo("3.6667");
            assertThat(shortWindow.path("priceByDay").size()).isEqualTo(1);
            priceDay(shortWindow.path("priceByDay").get(0), newDay, 3, "11", "3.666667");
            var longWindow = get(itemPath + "?days=90");
            totals(longWindow, 2, 5, "18");
            assertThat(longWindow.path("windowDays").asInt()).isEqualTo(90);
            assertThat(new BigDecimal(longWindow.path("avgUnitPrice").asText())).isEqualByComparingTo("3.6000");
            assertThat(longWindow.path("priceByDay").size()).isEqualTo(2);
            priceDay(longWindow.path("priceByDay").get(0), oldDay, 2, "7", "3.5000");
            priceDay(longWindow.path("priceByDay").get(1), newDay, 3, "11", "3.666667");
            // Separate cache keys must not let the 90-day view replace the 30-day view.
            assertThat(get(itemPath + "?days=30")).isEqualTo(shortWindow);
            var directory = get("/chestshop/items?search=" + material);
            assertThat(directory.path("items").size()).isEqualTo(1);
            totals(directory.path("items").get(0), 2, 5, "18");
            // Both trades now precede a quiet window; existing history must still yield 200.
            try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
                 var q = c.prepareStatement("UPDATE chestshop_sale SET occurred_at = UTC_DATE() - INTERVAL 10 DAY + INTERVAL 12 HOUR WHERE sale_id = ?")) {
                q.setLong(1, secondSale.saleId());
                assertThat(q.executeUpdate()).isEqualTo(1);
            }
            var quiet = get(itemPath + "?days=1");
            totals(quiet, 0, 0, "0");
            assertThat(new BigDecimal(quiet.path("avgUnitPrice").asText())).isEqualByComparingTo("0");
            assertThat(quiet.path("priceByDay").isEmpty()).isTrue();
            assertThat(quiet.path("activeShopCount").asInt()).isEqualTo(2);
            assertThat(quiet.path("cheapestShops").size()).isEqualTo(2);
            assertThat(quiet.path("material").asText()).isEqualTo(material);
            for (var shop : java.util.List.of(older, recent)) {
                shop.positionClicker(owner, owner.getUsername());
                Ops.run(owner.getUsername(), () -> {
                    RconClient.send("gamemode creative " + owner.getUsername());
                    Barrier.sync(owner);
                    owner.breakBlock(shop.signPos());
                    Barrier.sync(owner);
                });
                Await.until("removed historical item's shop deactivated", () -> Market.shopAt("world", shop.signPos().x(), shop.signPos().y(), shop.signPos().z()).orElseThrow().active(), active -> !active, TIMEOUT);
            }
            // A distinct window avoids a cached live-shop response; this is not cache-expiry coverage.
            var historical = get(itemPath + "?days=2");
            totals(historical, 0, 0, "0");
            assertThat(new BigDecimal(historical.path("avgUnitPrice").asText())).isEqualByComparingTo("0");
            assertThat(historical.path("priceByDay").isEmpty()).isTrue();
            assertThat(historical.path("activeShopCount").asInt()).isZero();
            assertThat(historical.path("cheapestShops").isEmpty()).isTrue();
            assertThat(historical.path("itemKey").asText()).isEqualTo(registered.itemKey());
            assertThat(historical.path("material").asText()).isEqualTo(material);
            var lifetime = get(itemPath + "?days=365");
            totals(lifetime, 2, 5, "18");
            assertThat(lifetime.path("activeShopCount").asInt()).isZero();
            assertThat(lifetime.path("cheapestShops").isEmpty()).isTrue();
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow()).isEqualByComparingTo("9982");
            assertThat(BusinessLedger.firmDefaultBalance(firmId).orElseThrow()).isEqualByComparingTo("18");
            assertThat(Inv.playerCount(buyer.getUsername(), material)).isEqualTo(5);
        }
    }
    private static String day(java.sql.Connection c, long sale) throws Exception {
        try (var q = c.prepareStatement("SELECT DATE_FORMAT(occurred_at, '%Y-%m-%d') FROM chestshop_sale WHERE sale_id = ?")) {
            q.setLong(1, sale);
            try (var r = q.executeQuery()) { assertThat(r.next()).isTrue(); return r.getString(1); }
        }
    }
    private static void priceDay(JsonNode day, String date, int quantity, String volume, String average) {
        assertThat(day.path("day").asText()).isEqualTo(date);
        assertThat(day.path("sales").asInt()).isEqualTo(1);
        assertThat(day.path("totalQuantity").asInt()).isEqualTo(quantity);
        assertThat(new BigDecimal(day.path("totalVolume").asText())).isEqualByComparingTo(volume);
        assertThat(new BigDecimal(day.path("avgUnitPrice").asText())).isEqualByComparingTo(average);
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
}
