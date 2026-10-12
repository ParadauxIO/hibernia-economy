package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChestShop's native Nexo custom-item integration (PAR-291), end-to-end. Backed by the
 * nexo-stub plugin (registers under the name "Nexo" so the softdepend hook fires, and
 * defines the custom item {@code test_ruby}). Verifies the full path: a shop is created
 * for a {@code nexo:test_ruby} item line (ItemService.parse resolves it via the Nexo
 * bridge → the custom ItemStack), the chest is stocked with the custom item, and a buyer
 * receives the custom item — asserted by its {@code nexo:id}, not just its material.
 */
class NexoCustomItemTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int QTY = 4;
    private static final int PRICE = 250;

    @Test
    void buyResolvesAndDeliversANexoCustomItem() {
        String seller = Usernames.fresh("Nxs");
        String buyer = Usernames.fresh("Nxb");

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            // Create the shop for the Nexo item. The item line is the bare id "test_ruby"
            // (ChestShop's sign pattern rejects colons, so Nexo items sit on signs bare and
            // resolve via acceptBareIds) — creation runs ItemService.parse, which must resolve
            // it through the Nexo bridge to the registered custom item.
            String created = shop.createSellShopRaw(seller, seller, QTY, PRICE, "test_ruby");
            assertThat(created).as("shop for a Nexo item should create").contains("cancelled=false");

            // Stock the (empty) chest with the actual Nexo item via the stub.
            String stockResp = RconClient.send(String.format("nexostock world %d %d %d test_ruby %d",
                    shop.chestPos().x(), shop.chestPos().y(), shop.chestPos().z(), QTY * 3));
            assertThat(stockResp).as("stub should stock the chest with the custom item").contains("nexostock test_ruby");
            RconClient.send("diag warmname " + seller);

            // Baseline: the buyer holds none of the custom item.
            assertThat(RconClient.send("nexocount " + buyer + " test_ruby")).contains("nexocount test_ruby = 0");

            shop.clickBuy(b, buyer);

            // Money moved, and the buyer received the CUSTOM item (asserted by nexo:id).
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING.subtract(BigDecimal.valueOf(PRICE)), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(), STARTING.add(BigDecimal.valueOf(PRICE)), TIMEOUT);

            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            String last = "";
            while (System.nanoTime() < deadline) {
                last = RconClient.send("nexocount " + buyer + " test_ruby");
                if (last.contains("nexocount test_ruby = " + QTY)) {
                    break;
                }
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            assertThat(last).as("buyer should receive %d of the Nexo custom item", QTY)
                    .contains("nexocount test_ruby = " + QTY);
        }
    }
}
