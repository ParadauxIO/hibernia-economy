package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class MarketItemPaginationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void tradedItemsRankByCountThenNameAcrossPopulatedAndEmptyPages() throws Exception {
        String firm = Usernames.fresh("PageF");
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("PageO"));
             var buyer = MockPlayer.connectLocal(Usernames.fresh("PageB"));
             var white = ChestShopFixture.create();
             var blue = ChestShopFixture.create();
             var red = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(buyer.getUuid(), TIMEOUT);
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business create " + firm);
                owner.awaitMessageContaining("Successfully created", TIMEOUT);
            });
            int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int account = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
            String signOwner = "B:" + Integer.toString(account, 36).toUpperCase();
            Ops.run(owner.getUsername(), () -> {
                assertThat(white.createSellShop(owner.getUsername(), signOwner, 2, 3, "WHITE_GLAZED_TERRACOTTA")).contains("cancelled=false");
                assertThat(blue.createSellShop(owner.getUsername(), signOwner, 1, 100, "BLUE_GLAZED_TERRACOTTA")).contains("cancelled=false");
                assertThat(red.createSellShop(owner.getUsername(), signOwner, 3, 7, "RED_GLAZED_TERRACOTTA")).contains("cancelled=false");
            });
            // Deliberately trade in a different order from both volume and expected ranking.
            red.clickBuy(buyer, buyer.getUsername());
            white.clickBuy(buyer, buyer.getUsername());
            blue.clickBuy(buyer, buyer.getUsername());
            white.clickBuy(buyer, buyer.getUsername());
            Market.waitForSaleCount(buyer.getUuid(), 4, TIMEOUT);
            Barrier.settle();
            long checkpoint = Ledger.transactionCheckpoint();
            String base = "/chestshop/items?search=GLAZED_TERRACOTTA";
            var first = page(base + "&page=1&limit=2", 1, 2, 3, "WHITE_GLAZED_TERRACOTTA", "BLUE_GLAZED_TERRACOTTA");
            row(first.path("items").get(0), 2, 4, "6");
            row(first.path("items").get(1), 1, 1, "100");
            var second = page(base + "&page=2&limit=2", 2, 2, 3, "RED_GLAZED_TERRACOTTA");
            row(second.path("items").get(0), 1, 3, "7");
            page(base + "&page=3&limit=2", 3, 2, 3);
            page(base + "&page=1&limit=1", 1, 3, 3, "WHITE_GLAZED_TERRACOTTA");
            page(base + "&page=2&limit=1", 2, 3, 3, "BLUE_GLAZED_TERRACOTTA");
            page(base + "&page=3&limit=1", 3, 3, 3, "RED_GLAZED_TERRACOTTA");
            // Display-name search uses spaces, unlike the material identifier.
            var filtered = page("/chestshop/items?search=Blue%20Glazed&limit=2", 1, 1, 1, "BLUE_GLAZED_TERRACOTTA");
            row(filtered.path("items").get(0), 1, 1, "100");
            assertThat(get(base + "&page=1&limit=2")).isEqualTo(first);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            assertThat(Market.saleCountForCustomer(buyer.getUuid())).isEqualTo(4);
            assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow()).isEqualByComparingTo("9887");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(BusinessLedger.firmDefaultBalance(firmId).orElseThrow()).isEqualByComparingTo("113");
            assertThat(Inv.playerCount(buyer.getUsername(), "WHITE_GLAZED_TERRACOTTA")).isEqualTo(4);
            assertThat(Inv.playerCount(buyer.getUsername(), "BLUE_GLAZED_TERRACOTTA")).isEqualTo(1);
            assertThat(Inv.playerCount(buyer.getUsername(), "RED_GLAZED_TERRACOTTA")).isEqualTo(3);
            // Keep the vanilla/custom regression active, after the independent pagination checks.
            assertThat(first.path("items").get(0).path("itemCustom").asBoolean())
                    .as("ordinary white glazed terracotta must not become custom because its sign name is shortened")
                    .isFalse();
        }
    }

    private static JsonNode page(String path, int page, int pages, int total, String... materials) {
        var json = get(path);
        assertThat(json.path("page").asInt()).isEqualTo(page);
        assertThat(json.path("totalPages").asInt()).isEqualTo(pages);
        assertThat(json.path("totalItems").asInt()).isEqualTo(total);
        var found = new java.util.ArrayList<String>();
        json.path("items").forEach(item -> found.add(item.path("material").asText()));
        assertThat(found).containsExactly(materials);
        return json;
    }

    private static void row(JsonNode item, int count, int quantity, String volume) {
        assertThat(item.path("tradeCount").asInt()).isEqualTo(count);
        assertThat(item.path("totalQuantity").asInt()).isEqualTo(quantity);
        assertThat(new BigDecimal(item.path("totalVolume").asText())).isEqualByComparingTo(volume);
    }

    private static JsonNode get(String path) {
        var response = RestApi.call("GET", path, null, null);
        assertThat(response.status()).as("%s: %s", path, response.body()).isEqualTo(200);
        return response.json();
    }
}
