package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class MarketVisibilityTest extends MarketApiTestSupport {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void findVisibilityToggleDoesNotDeactivateShopOrDisableDirectTrading() throws Exception {
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("VisO"));
             var buyer = MockPlayer.connectLocal(Usernames.fresh("VisB"));
             var shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(buyer.getUuid(), TIMEOUT);
            Ops.run(owner.getUsername(), () -> assertThat(shop.createSellShop(owner.getUsername(),
                    owner.getUsername(), 2, 9, "BASALT")).contains("cancelled=false"));
            var pos = shop.signPos();
            var before = Market.waitForShopAt("world", pos.x(), pos.y(), pos.z(), TIMEOUT);
            shop.positionClicker(owner, owner.getUsername());
            owner.chatCommand("/cs toggle visibility false");
            owner.awaitMessageContaining("now hidden", TIMEOUT);
            Barrier.settle();
            var hidden = Market.shopAt("world", pos.x(), pos.y(), pos.z()).orElseThrow();
            assertThat(hidden.visible()).isFalse();
            assertThat(hidden.active()).isTrue();
            assertThat(hidden.shopId()).isEqualTo(before.shopId());
            long checkpoint = Ledger.transactionCheckpoint();
            // Characterization: HTTP directory is active-shop based; visibility is a /find control.
            publicOffer(before.shopId(), 24);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            shop.clickBuy(buyer, buyer.getUsername());
            var sale = Market.waitForSaleForCustomer(buyer.getUuid(), TIMEOUT);
            Barrier.settle();
            assertThat(sale.quantity()).isEqualTo(2);
            assertThat(sale.totalPrice()).isEqualByComparingTo("9");
            assertThat(sale.shopOwnerUuid()).isEqualTo(owner.getUuid());
            var traded = Market.shopAt("world", pos.x(), pos.y(), pos.z()).orElseThrow();
            assertThat(traded.visible()).as("trade must not reset hidden preference").isFalse();
            assertThat(traded.active()).isTrue();
            assertThat(traded.shopId()).isEqualTo(before.shopId());
            publicOffer(before.shopId(), 22);
            long afterTrade = Ledger.transactionCheckpoint();
            shop.positionClicker(owner, owner.getUsername());
            owner.chatCommand("/cs toggle visibility true");
            owner.awaitMessageContaining("now shown", TIMEOUT);
            Barrier.settle();
            assertThat(Market.shopAt("world", pos.x(), pos.y(), pos.z()).orElseThrow().visible()).isTrue();
            publicOffer(before.shopId(), 22);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(afterTrade);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10009");
            assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow()).isEqualByComparingTo("9991");
            assertThat(Inv.playerCount(buyer.getUsername(), "BASALT")).isEqualTo(2);
            assertThat(Inv.chestCount(shop.chestPos(), "BASALT")).isEqualTo(22);
            assertThat(Market.saleCountForCustomer(buyer.getUuid())).isEqualTo(1);
        }
    }

    private void publicOffer(long id, int stock) {
        var response = RestApi.call("GET", "/chestshop/shops?material=BASALT&buyable=true&inStock=true", marketToken(), null);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        var json = response.json();
        assertThat(json.path("totalItems").asInt()).isEqualTo(1);
        assertThat(json.path("items").size()).isEqualTo(1);
        var row = json.path("items").get(0);
        assertThat(row.path("shopId").asLong()).isEqualTo(id);
        assertThat(row.path("currentStock").asInt()).isEqualTo(stock);
        assertThat(row.path("buyPrice").asText()).isEqualTo("9.00");
        assertThat(row.path("batchQty").asInt()).isEqualTo(2);
    }
}
