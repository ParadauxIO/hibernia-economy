package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.BlockFace;
import io.paradaux.mockclient.MockPlayer;
import io.paradaux.mockclient.Position;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Inv;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Market;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the ChestShop sales tracker + live shop registry: a real
 * in-game trade must flow ChestShop → Treasury {@code MarketApi} →
 * {@code chestshop_sale}/{@code chestshop_shop}. Mirrors {@link BusinessShopTest}
 * / {@link PersonalShopTest} for the trade mechanics, then asserts the captured
 * analytics rows (which the unit/mapper ITs can't prove — only a live server can).
 *
 * <p>The sign is placed via {@code setblock} (no {@code ShopCreatedEvent}), so the
 * registry row appearing also proves the lazy upsert-on-trade path.
 */
class ChestShopSalesTrackerE2ETest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final String WORLD = "world";

    @Test
    void businessShopTrade_recordsSaleAndRegistersShop() {
        String proprietor = Usernames.fresh("PrO");
        String buyer = Usernames.fresh("Byr");
        String firm = "Mkt" + Long.toHexString(Instant.now().toEpochMilli());
        int qty = 5;
        int price = 100;

        try (MockPlayer p = MockPlayer.connectLocal(proprietor);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(p.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            final int[] firmIdHolder = new int[1];
            Ops.run(proprietor, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                firmIdHolder[0] = BusinessLedger.waitForFirm(firm, TIMEOUT);
            });
            int firmId = firmIdHolder[0];
            int firmAccountId = BusinessLedger.firmDefaultAccountId(firmId)
                    .orElseThrow(() -> new AssertionError("firm has no default account"));
            String signOwner = "B:" + Integer.toString(firmAccountId, 36).toUpperCase();

            shop.setupSellShop(signOwner, qty, price, "dirt");
            buyOnce(b, buyer, shop);

            // trade completed (money moved) — preconditions the capture rides on
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING.subtract(BigDecimal.valueOf(price)), TIMEOUT);

            // ── sales tracker: the trade was captured, type-aware (firm) ──
            Market.Sale sale = Market.waitForSaleForCustomer(b.getUuid(), TIMEOUT);
            assertThat(sale.direction()).isEqualTo("BUY");
            assertThat(sale.shopAccountType()).isEqualTo("BUSINESS");
            assertThat(sale.shopFirmId()).isEqualTo(firmId);
            assertThat(sale.adminShop()).isFalse();
            assertThat(sale.shopOwnerUuid()).isNull();
            assertThat(sale.material()).isEqualTo("DIRT");
            assertThat(sale.quantity()).isEqualTo(qty);
            assertThat(sale.totalPrice()).isEqualByComparingTo(BigDecimal.valueOf(price));
            assertThat(sale.world()).isEqualTo(WORLD);

            // ── live registry: lazily registered on the trade (no ShopCreatedEvent) ──
            Market.Shop reg = Market.waitForShopAt(WORLD, shop.signPos().x(), shop.signPos().y(),
                    shop.signPos().z(), TIMEOUT);
            assertThat(reg.shopAccountType()).isEqualTo("BUSINESS");
            assertThat(reg.shopFirmId()).isEqualTo(firmId);
            assertThat(reg.material()).isEqualTo("DIRT");
            assertThat(reg.batchQty()).isEqualTo(qty);
            assertThat(reg.active()).isTrue();
            assertThat(reg.buyPrice()).isEqualByComparingTo(BigDecimal.valueOf(price));
        }
    }

    @Test
    void personalShopTrade_recordsTypeAwareOwner() {
        String seller = Usernames.fresh("Slr");
        String buyer = Usernames.fresh("Byr");
        int qty = 3;
        int price = 50;

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            shop.setupSellShop(seller, qty, price, "dirt");
            RconClient.send("diag warmname " + seller);
            buyOnce(b, buyer, shop);

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING.add(BigDecimal.valueOf(price)), TIMEOUT);

            Market.Sale sale = Market.waitForSaleForCustomer(b.getUuid(), TIMEOUT);
            assertThat(sale.direction()).isEqualTo("BUY");
            assertThat(sale.shopAccountType()).isEqualTo("PERSONAL");
            assertThat(sale.shopOwnerUuid()).isEqualTo(s.getUuid());
            assertThat(sale.shopFirmId()).isNull();
            assertThat(sale.adminShop()).isFalse();
            assertThat(sale.material()).isEqualTo("DIRT");
            assertThat(sale.quantity()).isEqualTo(qty);
            assertThat(sale.totalPrice()).isEqualByComparingTo(BigDecimal.valueOf(price));
        }
    }

    @Test
    void repeatTrades_accumulateSalesAndUpdateRegistryStock() {
        String proprietor = Usernames.fresh("PrO");
        String buyer = Usernames.fresh("Byr");
        String firm = "Mkt" + Long.toHexString(Instant.now().toEpochMilli());
        int qty = 4;
        int price = 25;

        try (MockPlayer p = MockPlayer.connectLocal(proprietor);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(p.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            final int[] firmIdHolder = new int[1];
            Ops.run(proprietor, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                firmIdHolder[0] = BusinessLedger.waitForFirm(firm, TIMEOUT);
            });
            int firmAccountId = BusinessLedger.firmDefaultAccountId(firmIdHolder[0])
                    .orElseThrow(() -> new AssertionError("firm has no default account"));
            String signOwner = "B:" + Integer.toString(firmAccountId, 36).toUpperCase();

            shop.setupSellShop(signOwner, qty, price, "dirt");
            int initialChestStock = Inv.chestCount(shop.chestPos(), "DIRT");

            buyOnce(b, buyer, shop);
            Market.waitForSaleCount(b.getUuid(), 1, TIMEOUT);
            // second purchase — repeat the full teleport/position-sync preamble so
            // the click reliably lands (a bare second useBlock gets dropped).
            buyOnce(b, buyer, shop);

            Market.waitForSaleCount(b.getUuid(), 2, TIMEOUT);
            assertThat(Market.saleCountForCustomer(b.getUuid())).isEqualTo(2);

            // registry reflects the latest post-trade stock (initial − 2 batches)
            Inv.waitForChestCount(shop.chestPos(), "DIRT", initialChestStock - 2 * qty, TIMEOUT);
            Market.Shop reg = Market.waitForShopAt(WORLD, shop.signPos().x(), shop.signPos().y(),
                    shop.signPos().z(), TIMEOUT);
            assertThat(reg.currentStock()).isEqualTo(initialChestStock - 2 * qty);
        }
    }

    /** Teleport the buyer into range and right-click the sign to buy one batch. */
    private static void buyOnce(MockPlayer b, String buyer, ChestShopFixture shop) {
        shop.clickBuy(b, buyer);
    }
}
