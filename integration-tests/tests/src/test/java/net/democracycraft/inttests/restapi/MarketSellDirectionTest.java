package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class MarketSellDirectionTest extends MarketApiTestSupport {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void customerSalePopulatesHistoryButDoesNotAdvertiseACustomerBuyOffer() throws Exception {
        String material = "RAW_COPPER_BLOCK";
        String firm = Usernames.fresh("SellF");
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("SellO"));
             var customer = MockPlayer.connectLocal(Usernames.fresh("SellC"));
             var shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(customer.getUuid(), TIMEOUT);
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business create " + firm);
                owner.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                Barrier.settle();
                owner.chatCommand("/business deposit " + firm + " 100");
                owner.awaitMessageContaining("Paid", TIMEOUT);
            });
            int firmId = BusinessLedger.firmId(firm).orElseThrow();
            int account = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
            // Direct sign fixture; the customer trade below uses the real player packet path.
            shop.setupBuyShop("B:" + Integer.toString(account, 36).toUpperCase(), 4, 13, material);
            Inv.give(customer.getUsername(), material, 4);
            Inv.waitForPlayerCount(customer.getUsername(), material, 4, TIMEOUT);
            shop.clickSell(customer, customer.getUsername());
            var sale = Market.waitForSaleForCustomer(customer.getUuid(), TIMEOUT);
            Barrier.settle();
            assertThat(sale.direction()).isEqualTo("SELL");
            assertThat(sale.shopAccountType()).isEqualTo("BUSINESS");
            assertThat(sale.shopFirmId()).isEqualTo(firmId);
            assertThat(sale.material()).isEqualTo(material);
            assertThat(sale.quantity()).isEqualTo(4);
            assertThat(sale.totalPrice()).isEqualByComparingTo("13");
            assertThat(sale.unitPrice()).isEqualByComparingTo("3.25");
            assertThat(Market.saleCountForCustomer(customer.getUuid())).isEqualTo(1);
            Inv.waitForChestCount(shop.chestPos(), material, 4, TIMEOUT);
            Inv.waitForPlayerCount(customer.getUsername(), material, 0, TIMEOUT);
            long checkpoint = Ledger.transactionCheckpoint();
            String base = "/chestshop/shops?firmId=" + firmId;
            var directory = get(base);
            assertThat(directory.path("totalItems").asInt()).isEqualTo(1);
            assertThat(directory.path("items").size()).isEqualTo(1);
            var row = directory.path("items").get(0);
            assertThat(row.path("buyPrice").isNull()).isTrue();
            assertThat(row.path("sellPrice").asText()).isEqualTo("13.00");
            assertThat(row.path("currentStock").asInt()).isEqualTo(4);
            assertThat(row.path("batchQty").asInt()).isEqualTo(4);
            assertThat(row.path("accountType").asText()).isEqualTo("BUSINESS");
            assertThat(row.path("firmId").asInt()).isEqualTo(firmId);
            assertThat(row.path("itemKey").asText()).isEqualTo(sale.itemKey());
            assertThat(get(base + "&inStock=true")).isEqualTo(directory);
            for (String filter : new String[]{"&buyable=true", "&buyable=true&inStock=true"}) {
                var empty = get(base + filter);
                assertThat(empty.path("totalItems").asInt()).isZero();
                assertThat(empty.path("items").size()).isZero();
            }
            String key = java.net.URLEncoder.encode(sale.itemKey(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            var detail = get("/chestshop/items/" + key + "?days=30");
            totals(detail);
            assertThat(detail.path("material").asText()).isEqualTo(material);
            assertThat(detail.path("activeShopCount").asInt()).isEqualTo(1);
            assertThat(detail.path("cheapestShops").size()).isZero();
            assertThat(new BigDecimal(detail.path("avgUnitPrice").asText())).isEqualByComparingTo("3.25");
            var items = get("/chestshop/items?search=" + material);
            assertThat(items.path("totalItems").asInt()).isEqualTo(1);
            assertThat(items.path("items").size()).isEqualTo(1);
            totals(items.path("items").get(0));
            assertThat(Ledger.personalBalance(customer.getUuid()).orElseThrow()).isEqualByComparingTo("10013");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("9900");
            assertThat(BusinessLedger.firmDefaultBalance(firmId).orElseThrow()).isEqualByComparingTo("87");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }

    private void totals(JsonNode node) {
        assertThat(node.path("tradeCount").asInt()).isEqualTo(1);
        assertThat(node.path("totalQuantity").asInt()).isEqualTo(4);
        assertThat(new BigDecimal(node.path("totalVolume").asText())).isEqualByComparingTo("13");
    }

    private JsonNode get(String path) {
        var response = RestApi.call("GET", path, marketToken(), null);
        assertThat(response.status()).as("%s: %s", path, response.body()).isEqualTo(200);
        return response.json();
    }
}
