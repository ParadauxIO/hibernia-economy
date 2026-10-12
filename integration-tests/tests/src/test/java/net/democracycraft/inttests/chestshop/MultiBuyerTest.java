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
 * Three concurrent buyers each transact once against a single shop. Verifies
 * cumulative chest drain, cumulative seller (or firm) credit, and per-buyer
 * debit. One personal-shop variant + one business-shop variant.
 *
 * <p>This catches bugs where a transaction posts twice for one player or
 * once for none — i.e. the audit trail is wrong even though every individual
 * trade looks fine in isolation.
 */
class MultiBuyerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int QTY = 5;
    private static final int PRICE = 100;

    @Test
    void threeBuyersSequentialAgainstPersonalShop() {
        String seller = Usernames.fresh("Sm");
        String[] buyers = { Usernames.fresh("B1"), Usernames.fresh("B2"), Usernames.fresh("B3") };

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b1 = MockPlayer.connectLocal(buyers[0]);
             MockPlayer b2 = MockPlayer.connectLocal(buyers[1]);
             MockPlayer b3 = MockPlayer.connectLocal(buyers[2]);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b1.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b2.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b3.getUuid(), STARTING, TIMEOUT);

            shop.setupSellShop(seller, QTY, PRICE, "dirt");
            int initialStock = Inv.chestCount(shop.chestPos(), "DIRT");
            assertThat(initialStock).isGreaterThanOrEqualTo(QTY * 3);

            RconClient.send("diag warmname " + seller);

            MockPlayer[] mocks = { b1, b2, b3 };
            for (int i = 0; i < buyers.length; i++) {
                buyOnce(mocks[i], buyers[i], shop);
            }

            // Seller credited 3× price; each buyer debited price.
            Ledger.waitForPersonalBalance(s.getUuid(),
                    STARTING.add(BigDecimal.valueOf((long) PRICE * 3)), TIMEOUT);
            for (int i = 0; i < buyers.length; i++) {
                Ledger.waitForPersonalBalance(mocks[i].getUuid(),
                        STARTING.subtract(BigDecimal.valueOf(PRICE)), TIMEOUT);
            }
            // Items: chest down by 3*QTY, each buyer up by QTY.
            Inv.waitForChestCount(shop.chestPos(), "DIRT", initialStock - QTY * 3, TIMEOUT);
            for (String b : buyers) {
                Inv.waitForPlayerCount(b, "DIRT", QTY, TIMEOUT);
            }
        }
    }

    @Test
    void threeBuyersSequentialAgainstBusinessShop() {
        String proprietor = Usernames.fresh("Pr");
        String[] buyers = { Usernames.fresh("B1"), Usernames.fresh("B2"), Usernames.fresh("B3") };
        String firm = "Mbb" + Long.toHexString(Instant.now().toEpochMilli());

        try (MockPlayer p = MockPlayer.connectLocal(proprietor);
             MockPlayer b1 = MockPlayer.connectLocal(buyers[0]);
             MockPlayer b2 = MockPlayer.connectLocal(buyers[1]);
             MockPlayer b3 = MockPlayer.connectLocal(buyers[2]);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(p.getUuid(), STARTING, TIMEOUT);
            for (MockPlayer b : new MockPlayer[]{b1, b2, b3}) {
                Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);
            }

            Ops.run(proprietor, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
            });
            int firmId = BusinessLedger.firmId(firm).orElseThrow();
            int firmAccountId = BusinessLedger.firmDefaultAccountId(firmId)
                    .orElseThrow(() -> new AssertionError("firm has no default account"));
            String signOwner = "B:" + Integer.toString(firmAccountId, 36).toUpperCase();
            BigDecimal firmBefore = BusinessLedger.firmDefaultBalance(firmId).orElse(BigDecimal.ZERO);

            shop.setupSellShop(signOwner, QTY, PRICE, "dirt");
            int initialStock = Inv.chestCount(shop.chestPos(), "DIRT");

            MockPlayer[] mocks = { b1, b2, b3 };
            for (int i = 0; i < buyers.length; i++) {
                buyOnce(mocks[i], buyers[i], shop);
            }

            // Firm BUSINESS account credited 3×; each buyer debited once.
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            BigDecimal expectedFirm = firmBefore.add(BigDecimal.valueOf((long) PRICE * 3));
            BigDecimal lastFirm = null;
            while (System.nanoTime() < deadline) {
                lastFirm = BusinessLedger.firmDefaultBalance(firmId).orElse(null);
                if (lastFirm != null && lastFirm.compareTo(expectedFirm) == 0) break;
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            assertThat(lastFirm).isEqualByComparingTo(expectedFirm);
            for (int i = 0; i < buyers.length; i++) {
                Ledger.waitForPersonalBalance(mocks[i].getUuid(),
                        STARTING.subtract(BigDecimal.valueOf(PRICE)), TIMEOUT);
            }
            // Items: chest down by 3*QTY, each buyer up by QTY.
            Inv.waitForChestCount(shop.chestPos(), "DIRT", initialStock - QTY * 3, TIMEOUT);
            for (String b : buyers) {
                Inv.waitForPlayerCount(b, "DIRT", QTY, TIMEOUT);
            }
        }
    }

    private static void buyOnce(MockPlayer buyer, String name, ChestShopFixture shop) {
        shop.clickBuy(buyer, name);
    }
}
