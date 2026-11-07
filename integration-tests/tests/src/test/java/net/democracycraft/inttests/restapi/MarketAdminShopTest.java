package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class MarketAdminShopTest extends MarketApiTestSupport {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @AfterEach
    void restoreDefault() {
        RconClient.send("diag config forceUnlimitedAdminShop false");
    }

    @Test
    void unlimitedPurchaseFromEmptyChestRemainsAnInStockPublicOffer() throws Exception {
        String material = "CUT_COPPER";
        RconClient.send("diag config forceUnlimitedAdminShop true");
        try (var buyer = MockPlayer.connectLocal(Usernames.fresh("MAdm"));
             var shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(buyer.getUuid(), TIMEOUT);
            // Direct sign fixture, followed by a real customer purchase packet.
            shop.setupSellShop("Admin Shop", 3, 12, material);
            var chest = shop.chestPos();
            RconClient.send(String.format("data merge block %d %d %d {Items:[]}", chest.x(), chest.y(), chest.z()));
            assertThat(Inv.chestCount(chest, material)).isZero();
            shop.clickBuy(buyer, buyer.getUsername());
            var sale = Market.waitForSaleForCustomer(buyer.getUuid(), TIMEOUT);
            Barrier.settle();
            assertThat(sale.adminShop()).isTrue();
            assertThat(sale.direction()).isEqualTo("BUY");
            assertThat(sale.shopAccountType()).isNull();
            assertThat(sale.shopFirmId()).isNull();
            assertThat(sale.shopOwnerUuid()).isNull();
            assertThat(sale.quantity()).isEqualTo(3);
            assertThat(sale.totalPrice()).isEqualByComparingTo("12");
            assertThat(sale.unitPrice()).isEqualByComparingTo("4");
            var pos = shop.signPos();
            var registered = Market.waitForShopAt("world", pos.x(), pos.y(), pos.z(), TIMEOUT);
            assertThat(registered.currentStock()).isNull();
            long checkpoint = Ledger.transactionCheckpoint();
            for (String filter : new String[]{"", "&inStock=true", "&buyable=true&inStock=true"}) {
                var directory = get("/chestshop/shops?material=" + material + filter);
                assertThat(directory.path("totalItems").asInt()).isEqualTo(1);
                assertThat(directory.path("items").size()).isEqualTo(1);
                offer(directory.path("items").get(0), registered.shopId());
            }
            String key = java.net.URLEncoder.encode(sale.itemKey(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            var detail = get("/chestshop/items/" + key + "?days=30");
            assertThat(detail.path("itemKey").asText()).isEqualTo(sale.itemKey());
            assertThat(detail.path("material").asText()).isEqualTo(material);
            totals(detail);
            assertThat(new BigDecimal(detail.path("avgUnitPrice").asText())).isEqualByComparingTo("4");
            assertThat(detail.path("activeShopCount").asInt()).isEqualTo(1);
            assertThat(detail.path("cheapestShops").size()).isEqualTo(1);
            offer(detail.path("cheapestShops").get(0), registered.shopId());
            var directory = get("/chestshop/items?search=" + material);
            assertThat(directory.path("totalItems").asInt()).isEqualTo(1);
            assertThat(directory.path("items").size()).isEqualTo(1);
            totals(directory.path("items").get(0));
            assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow()).isEqualByComparingTo("9988");
            assertThat(Inv.playerCount(buyer.getUsername(), material)).isEqualTo(3);
            assertThat(Inv.chestCount(chest, material)).isZero();
            assertThat(Market.saleCountForCustomer(buyer.getUuid())).isEqualTo(1);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }

    private void offer(JsonNode row, long shopId) {
        assertThat(row.path("shopId").asLong()).isEqualTo(shopId);
        assertThat(row.path("adminShop").asBoolean()).isTrue();
        for (String field : new String[]{"currentStock", "currentSpace", "accountType", "firmId", "ownerName"}) {
            assertThat(row.path(field).isNull()).as(field).isTrue();
        }
        assertThat(row.path("buyPrice").asText()).isEqualTo("12.00");
        assertThat(row.path("sellPrice").isNull()).isTrue();
        assertThat(row.path("batchQty").asInt()).isEqualTo(3);
    }

    private void totals(JsonNode node) {
        assertThat(node.path("tradeCount").asInt()).isEqualTo(1);
        assertThat(node.path("totalQuantity").asInt()).isEqualTo(3);
        assertThat(new BigDecimal(node.path("totalVolume").asText())).isEqualByComparingTo("12");
    }

    private JsonNode get(String path) {
        var response = RestApi.call("GET", path, marketToken(), null);
        assertThat(response.status()).as("%s: %s", path, response.body()).isEqualTo(200);
        return response.json();
    }
}
