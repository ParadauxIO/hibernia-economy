package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.assertThat;

class ShopDirectoryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    @Test
    void publicDirectoryFiltersPagesAndReflectsRealBusinessShopTrade() {
        String firm = Usernames.fresh("DirF");
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("DirO"));
             var buyer = MockPlayer.connectLocal(Usernames.fresh("DirB"));
             var cheap = ChestShopFixture.create(); var stone = ChestShopFixture.create();
             var costly = ChestShopFixture.create()) {
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
                assertThat(cheap.createSellShop(owner.getUsername(), signOwner, 2, 7, "dirt")).contains("cancelled=false");
                assertThat(stone.createSellShop(owner.getUsername(), signOwner, 3, 11, "stone")).contains("cancelled=false");
                assertThat(costly.createSellShop(owner.getUsername(), signOwner, 1, 19, "dirt")).contains("cancelled=false");
            });
            var a = registered(cheap); var b = registered(stone); var c = registered(costly);
            String base = "/chestshop/shops?firmId=" + firmId;
            page(base + "&limit=2", 1, 2, 3, a.shopId(), b.shopId());
            page(base + "&limit=2&page=2", 2, 2, 3, c.shopId());
            page(base + "&limit=2&page=3", 3, 2, 3);
            var dirt = page(base + "&material=DIRT&buyable=true&inStock=true", 1, 1, 2, a.shopId(), c.shopId());
            row(dirt.path("items").get(0), cheap, a, firmId, firm, "DIRT", "7.00", 2, 24);
            row(dirt.path("items").get(1), costly, c, firmId, firm, "DIRT", "19.00", 1, 12);
            page(base + "&search=STONE", 1, 1, 1, b.shopId());
            page(base + "&material=DIAMOND", 1, 0, 0);
            page(base + "&itemKey=" + java.net.URLEncoder.encode(a.itemKey(), java.nio.charset.StandardCharsets.UTF_8), 1, 1, 2, a.shopId(), c.shopId());
            long checkpoint = Ledger.transactionCheckpoint();
            cheap.clickBuy(buyer, buyer.getUsername());
            Ledger.waitForPersonalBalance(buyer.getUuid(), new BigDecimal("9993"), TIMEOUT);
            BusinessLedger.waitForFirmDefaultBalance(firmId, new BigDecimal("7"), TIMEOUT);
            Barrier.settle();
            Await.until("directory reflects traded stock", () -> get(base + "&material=DIRT"),
                    json -> json.path("items").get(0).path("currentStock").asInt() == 22, TIMEOUT);
            var after = page(base + "&material=DIRT", 1, 1, 2, a.shopId(), c.shopId());
            row(after.path("items").get(0), cheap, a, firmId, firm, "DIRT", "7.00", 2, 22);
            assertThat(Inv.playerCount(buyer.getUsername(), "DIRT")).isEqualTo(2);
            assertThat(Inv.chestCount(cheap.chestPos(), "DIRT")).isEqualTo(22);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.transactionCheckpoint()).isGreaterThan(checkpoint);
            assertThat(Market.saleCountForCustomer(buyer.getUuid())).isEqualTo(1);
        }
    }
    @Test
    void soldOutOrderingStockFilterAndRemovedSignsFollowLiveState() {
        String firm = Usernames.fresh("DsFir");
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("DsOwn"));
             var buyer = MockPlayer.connectLocal(Usernames.fresh("DsBuy"));
             var empty = ChestShopFixture.create(); var stocked = ChestShopFixture.create()) {
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
                assertThat(empty.createSellShopRaw(owner.getUsername(), signOwner, 1, 7, "DIRT")).contains("cancelled=false");
                assertThat(stocked.createSellShop(owner.getUsername(), signOwner, 64, 11, "dirt")).contains("cancelled=false");
            });
            var a = registered(empty); var b = registered(stocked);
            String base = "/chestshop/shops?firmId=" + firmId;
            assertThat(a.currentStock()).isZero();
            assertThat(b.currentStock()).isEqualTo(64);
            // Availability precedes price: the more expensive stocked shop comes first.
            page(base, 1, 1, 2, b.shopId(), a.shopId());
            page(base + "&inStock=true", 1, 1, 1, b.shopId());
            page(base + "&buyable=true", 1, 1, 2, b.shopId(), a.shopId());
            stocked.clickBuy(buyer, buyer.getUsername());
            Ledger.waitForPersonalBalance(buyer.getUuid(), new BigDecimal("9989"), TIMEOUT);
            BusinessLedger.waitForFirmDefaultBalance(firmId, new BigDecimal("11"), TIMEOUT);
            Barrier.settle();
            Await.until("stocked shop is now sold out", () -> registered(stocked).currentStock(), n -> n == 0, TIMEOUT);
            page(base + "&inStock=true", 1, 0, 0);
            page(base + "&buyable=true", 1, 1, 2, a.shopId(), b.shopId());
            assertThat(Inv.playerCount(buyer.getUsername(), "DIRT")).isEqualTo(64);
            assertThat(Inv.chestCount(stocked.chestPos(), "DIRT")).isZero();
            long checkpoint = Ledger.transactionCheckpoint();
            empty.positionClicker(owner, owner.getUsername());
            Ops.run(owner.getUsername(), () -> {
                RconClient.send("gamemode creative " + owner.getUsername());
                Barrier.sync(owner);
                owner.breakBlock(empty.signPos());
                Barrier.sync(owner);
            });
            Await.until("sign break deactivates shop", () -> registered(empty).active(), active -> !active, TIMEOUT);
            page(base, 1, 1, 1, b.shopId());
            page(base + "&buyable=true", 1, 1, 1, b.shopId());
            page(base + "&inStock=true", 1, 0, 0);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow()).isEqualByComparingTo("9989");
            assertThat(BusinessLedger.firmDefaultBalance(firmId).orElseThrow()).isEqualByComparingTo("11");
            assertThat(Market.saleCountForCustomer(buyer.getUuid())).isEqualTo(1);
        }
    }
    private static Market.Shop registered(ChestShopFixture fixture) {
        var pos = fixture.signPos();
        return Market.waitForShopAt("world", pos.x(), pos.y(), pos.z(), TIMEOUT);
    }
    private static JsonNode get(String path) {
        var r = RestApi.call("GET", path, null, null);
        assertThat(r.status()).as("%s", r.body()).isEqualTo(200);
        return r.json();
    }
    private static JsonNode page(String path, int page, int pages, int count, long... ids) {
        var json = get(path);
        assertThat(json.path("page").asInt()).isEqualTo(page);
        assertThat(json.path("totalPages").asInt()).isEqualTo(pages);
        assertThat(json.path("totalItems").asInt()).isEqualTo(count);
        var found = new ArrayList<Long>();
        json.path("items").forEach(row -> found.add(row.path("shopId").asLong()));
        assertThat(found).containsExactly(java.util.Arrays.stream(ids).boxed().toArray(Long[]::new));
        return json;
    }
    private static void row(JsonNode row, ChestShopFixture fixture, Market.Shop shop, int firmId, String firm,
                            String material, String price, int batch, int stock) {
        assertThat(row.path("world").asText()).isEqualTo("world");
        assertThat(row.path("x").asInt()).isEqualTo(fixture.signPos().x());
        assertThat(row.path("y").asInt()).isEqualTo(fixture.signPos().y());
        assertThat(row.path("z").asInt()).isEqualTo(fixture.signPos().z());
        assertThat(row.path("adminShop").asBoolean()).isFalse();
        assertThat(row.path("accountType").asText()).isEqualTo("BUSINESS");
        assertThat(row.path("firmId").asInt()).isEqualTo(firmId);
        assertThat(row.path("ownerName").asText()).isEqualTo(firm);
        assertThat(row.path("material").asText()).isEqualTo(material);
        assertThat(row.path("itemKey").asText()).isEqualTo(shop.itemKey());
        assertThat(row.path("itemCustom").asBoolean()).isFalse();
        assertThat(row.path("buyPrice").asText()).isEqualTo(price);
        assertThat(row.path("sellPrice").isNull()).isTrue();
        assertThat(row.path("batchQty").asInt()).isEqualTo(batch);
        assertThat(row.path("currentStock").asInt()).isEqualTo(stock);
    }
}
