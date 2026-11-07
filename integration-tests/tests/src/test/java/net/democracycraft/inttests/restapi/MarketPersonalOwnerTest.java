package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class MarketPersonalOwnerTest extends MarketApiTestSupport {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void twoPersonalOwnersRetainDistinctPublicIdentitiesAndReceiveTheirOwnPayments() throws Exception {
        try (var first = MockPlayer.connectLocal(Usernames.fresh("OwnA"));
             var second = MockPlayer.connectLocal(Usernames.fresh("OwnB"));
             var buyer = MockPlayer.connectLocal(Usernames.fresh("OwnC"));
             var a = ChestShopFixture.create(); var b = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(first.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(second.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(buyer.getUuid(), TIMEOUT);
            Ops.run(first.getUsername(), () -> assertThat(a.createSellShop(first.getUsername(), first.getUsername(), 2, 7, "CALCITE")).contains("cancelled=false"));
            Ops.run(second.getUsername(), () -> assertThat(b.createSellShop(second.getUsername(), second.getUsername(), 3, 11, "CALCITE")).contains("cancelled=false"));
            a.clickBuy(buyer, buyer.getUsername());
            var saleA = Market.waitForSaleForCustomer(buyer.getUuid(), TIMEOUT);
            assertThat(saleA.shopOwnerUuid()).isEqualTo(first.getUuid());
            assertThat(saleA.shopAccountType()).isEqualTo("PERSONAL");
            assertThat(saleA.totalPrice()).isEqualByComparingTo("7");
            b.clickBuy(buyer, buyer.getUsername());
            Market.waitForSaleCount(buyer.getUuid(), 2, TIMEOUT);
            Barrier.settle();
            var saleB = Market.latestSaleForCustomer(buyer.getUuid()).orElseThrow();
            assertThat(saleB.shopOwnerUuid()).isEqualTo(second.getUuid());
            assertThat(saleB.shopAccountType()).isEqualTo("PERSONAL");
            assertThat(saleB.totalPrice()).isEqualByComparingTo("11");
            long checkpoint = Ledger.transactionCheckpoint();
            var directory = get("/chestshop/shops?material=CALCITE&buyable=true&inStock=true");
            assertThat(directory.path("totalItems").asInt()).isEqualTo(2);
            assertThat(directory.path("items").size()).isEqualTo(2);
            row(directory.path("items").get(0), first, a, "7.00", 2, 22);
            row(directory.path("items").get(1), second, b, "11.00", 3, 33);
            String key = java.net.URLEncoder.encode(saleA.itemKey(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            var detail = get("/chestshop/items/" + key + "?days=30");
            assertThat(detail.path("cheapestShops").size()).isEqualTo(2);
            row(detail.path("cheapestShops").get(0), first, a, "7.00", 2, 22);
            row(detail.path("cheapestShops").get(1), second, b, "11.00", 3, 33);
            assertThat(Ledger.personalBalance(first.getUuid()).orElseThrow()).isEqualByComparingTo("10007");
            assertThat(Ledger.personalBalance(second.getUuid()).orElseThrow()).isEqualByComparingTo("10011");
            assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow()).isEqualByComparingTo("9982");
            assertThat(Inv.playerCount(buyer.getUsername(), "CALCITE")).isEqualTo(5);
            assertThat(Inv.chestCount(a.chestPos(), "CALCITE")).isEqualTo(22);
            assertThat(Inv.chestCount(b.chestPos(), "CALCITE")).isEqualTo(33);
            assertThat(Market.saleCountForCustomer(buyer.getUuid())).isEqualTo(2);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }

    private void row(JsonNode row, MockPlayer owner, ChestShopFixture fixture, String price, int batch, int stock) {
        var pos = fixture.signPos();
        var registered = Market.waitForShopAt("world", pos.x(), pos.y(), pos.z(), TIMEOUT);
        assertThat(row.path("shopId").asLong()).isEqualTo(registered.shopId());
        assertThat(row.path("accountType").asText()).isEqualTo("PERSONAL");
        assertThat(UUID.fromString(row.path("ownerUuid").asText())).isEqualTo(owner.getUuid());
        assertThat(row.path("ownerName").asText()).isEqualTo(owner.getUsername());
        assertThat(row.path("firmId").isNull()).isTrue();
        assertThat(row.path("adminShop").asBoolean()).isFalse();
        assertThat(row.path("buyPrice").asText()).isEqualTo(price);
        assertThat(row.path("batchQty").asInt()).isEqualTo(batch);
        assertThat(row.path("currentStock").asInt()).isEqualTo(stock);
        assertThat(row.path("currentSpace").asInt()).isEqualTo(27 * 64 - stock);
    }

    private JsonNode get(String path) {
        var response = RestApi.call("GET", path, marketToken(), null);
        assertThat(response.status()).as("%s: %s", path, response.body()).isEqualTo(200);
        return response.json();
    }
}
