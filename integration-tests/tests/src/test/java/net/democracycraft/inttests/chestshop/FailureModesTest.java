package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.BlockFace;
import io.paradaux.mockclient.MockPlayer;
import io.paradaux.mockclient.Position;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Inv;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Negative-path coverage: a rejected trade must move no money and no items.
 * We assert against the ledger + container/inventory state (the ground truth) —
 * not the chat receipt: the io.paradaux fork renders shop messages through
 * component/hover paths the mock's plain-text capture flattens unreliably, so a
 * chat-receipt assertion is flaky by construction (the success-path tests avoid
 * it for the same reason). If the rejection didn't happen, the balance/stock
 * assertions catch it deterministically.
 *
 * <p>Personal- and business-shop variants for each scenario.
 */
class FailureModesTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int QTY = 5;
    private static final int PRICE = 100;

    // ---------------------------------------------------------------- out of stock

    @Test
    void emptyChestRejectsBuyOnPersonalShop() {
        runEmptyChestScenario(/*business=*/ false);
    }

    @Test
    void emptyChestRejectsBuyOnBusinessShop() {
        runEmptyChestScenario(/*business=*/ true);
    }

    private void runEmptyChestScenario(boolean business) {
        String seller = Usernames.fresh(business ? "Pr" : "Sl");
        String buyer = Usernames.fresh("By");
        String firm = business ? "Fbe" + Long.toHexString(Instant.now().toEpochMilli()) : null;

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            String signOwner = business ? createFirm(s, seller, firm) : seller;
            shop.setupSellShop(signOwner, QTY, PRICE, "dirt");
            // Drain the chest in-place. setblock-with-the-same-type preserves
            // NBT, and air-then-chest would drop the sign attached above.
            // `data merge block` mutates the existing block-entity NBT without
            // disturbing the sign.
            RconClient.send(String.format("data merge block %d %d %d {Items:[]}",
                    shop.chestPos().x(), shop.chestPos().y(), shop.chestPos().z()));
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isZero();

            BigDecimal buyerBefore = Ledger.personalBalance(b.getUuid()).orElseThrow();
            if (!business) RconClient.send("diag warmname " + seller);

            clickAt(b, buyer, shop);

            // Error chat receipt + no balance change.
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            assertThat(Ledger.personalBalance(b.getUuid()).orElseThrow()).isEqualByComparingTo(buyerBefore);
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isZero();
            assertThat(Inv.playerCount(buyer, "DIRT")).isZero();
        }
    }

    // ---------------------------------------------------------------- insufficient funds

    @Test
    void brokeBuyerRejectedOnPersonalShop() {
        runBrokeBuyerScenario(/*business=*/ false);
    }

    @Test
    void brokeBuyerRejectedOnBusinessShop() {
        runBrokeBuyerScenario(/*business=*/ true);
    }

    private void runBrokeBuyerScenario(boolean business) {
        String seller = Usernames.fresh(business ? "Pr" : "Sl");
        String buyer = Usernames.fresh("By");
        String firm = business ? "Fbf" + Long.toHexString(Instant.now().toEpochMilli()) : null;

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            String signOwner = business ? createFirm(s, seller, firm) : seller;
            shop.setupSellShop(signOwner, QTY, PRICE, "dirt");
            int initialStock = Inv.chestCount(shop.chestPos(), "DIRT");

            // Drain buyer's balance to $0.
            RconClient.send("eco take " + buyer + " 10000");
            Ledger.waitForPersonalBalance(b.getUuid(), BigDecimal.ZERO, TIMEOUT);

            if (!business) RconClient.send("diag warmname " + seller);

            clickAt(b, buyer, shop);
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // Buyer balance still 0, chest stock unchanged, buyer inventory empty.
            assertThat(Ledger.personalBalance(b.getUuid()).orElseThrow()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(initialStock);
            assertThat(Inv.playerCount(buyer, "DIRT")).isZero();
        }
    }

    // ---------------------------------------------------------------- full inventory

    @Test
    void fullInventoryRejectsBuyOnPersonalShop() {
        runFullInventoryScenario(/*business=*/ false);
    }

    @Test
    void fullInventoryRejectsBuyOnBusinessShop() {
        runFullInventoryScenario(/*business=*/ true);
    }

    private void runFullInventoryScenario(boolean business) {
        String seller = Usernames.fresh(business ? "Pr" : "Sl");
        String buyer = Usernames.fresh("By");
        String firm = business ? "Ffi" + Long.toHexString(Instant.now().toEpochMilli()) : null;

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            String signOwner = business ? createFirm(s, seller, firm) : seller;
            shop.setupSellShop(signOwner, QTY, PRICE, "dirt");
            int initialStock = Inv.chestCount(shop.chestPos(), "DIRT");
            BigDecimal buyerBefore = Ledger.personalBalance(b.getUuid()).orElseThrow();

            // Pad the buyer's inventory full of cobble. ChestShop counts COBBLE
            // not DIRT, so the dirt inventory check still reads zero before the
            // (rejected) trade.
            Inv.fill(buyer);
            assertThat(Inv.playerCount(buyer, "COBBLESTONE")).isEqualTo(36 * 64);

            if (!business) RconClient.send("diag warmname " + seller);

            clickAt(b, buyer, shop);
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            assertThat(Ledger.personalBalance(b.getUuid()).orElseThrow()).isEqualByComparingTo(buyerBefore);
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(initialStock);
            assertThat(Inv.playerCount(buyer, "DIRT")).isZero();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String createFirm(MockPlayer proprietorMock, String proprietor, String firm) {
        Ops.run(proprietor, () -> {
            proprietorMock.chatCommand("/business create " + firm);
            proprietorMock.awaitMessageContaining("Successfully created", TIMEOUT);
            BusinessLedger.waitForFirm(firm, TIMEOUT);
        });
        int firmId = BusinessLedger.firmId(firm).orElseThrow();
        int firmAccountId = BusinessLedger.firmDefaultAccountId(firmId)
                .orElseThrow(() -> new AssertionError("firm has no default account"));
        return "B:" + Integer.toString(firmAccountId, 36).toUpperCase();
    }

    private static void clickAt(MockPlayer buyer, String name, ChestShopFixture shop) {
        shop.clickBuy(buyer, name);
    }
}
