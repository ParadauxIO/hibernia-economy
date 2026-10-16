package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Inv;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ALLOW_FREE_SHOPS} governs zero-price ({@code 0 B}) shops. On DemocracyCraft
 * it's off (the production default): a buyer using a free shop has its sign POPPED
 * OFF (removed via {@code breakNaturally}) instead of trading — free shops aren't
 * wanted (PAR-88). This asserts that production behaviour.
 *
 * <p>FINDING — the <em>enabled</em> path is broken, but NOT in the free-shop rejection
 * logic (traced with instance-level debug): with {@code ALLOW_FREE_SHOPS} on,
 * {@code flagFreeShop} and {@code rejectFreeShop} correctly read the flag as {@code true}
 * (same Guice instance, verified via {@code diag configget}) and do NOT flag the shop.
 * The 0-price <em>trade</em> still fails: the buyer receives nothing and the shop's chest
 * then sign are destroyed via a physics {@code BlockDestroyEvent} (chest support gone),
 * confirmed for both {@code /setblock} and really-created shops. No ChestShop code
 * removes the chest (the only container-remover, {@code deleteEmptyShop}, is gated off by
 * {@code REMOVE_EMPTY_SHOPS=false}), so the removal is a vanilla physics cascade the free
 * path triggers where priced shops don't. A real bug, but non-production (free shops are
 * intentionally off), so no green test asserts the enabled path.
 */
class FreeShopConfigTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int QTY = 5;

    @Test
    void freeShopSignPopsOffWhenDisabled() {
        RconClient.send("diag config allowFreeShops false");
        String seller = Usernames.fresh("Fds");
        String buyer = Usernames.fresh("Fdb");

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            shop.setupSellShop(seller, QTY, 0, "dirt"); // 0 B
            int initialStock = Inv.chestCount(shop.chestPos(), "DIRT");
            RconClient.send("diag warmname " + seller);

            shop.clickBuy(b, buyer);
            // The click returned only once the server had processed it (ChestShopFixture#click), so the rejection is final.

            // The sign is gone and nothing traded.
            String sign = RconClient.send("diag checksign world "
                    + shop.signPos().x() + " " + shop.signPos().y() + " " + shop.signPos().z());
            assertThat(sign).as("free-shop sign should pop off").contains("type=AIR");
            assertThat(Ledger.personalBalance(b.getUuid()).orElseThrow()).isEqualByComparingTo(STARTING);
            assertThat(Ledger.personalBalance(s.getUuid()).orElseThrow()).isEqualByComparingTo(STARTING);
            assertThat(Inv.playerCount(buyer, "DIRT")).isZero();
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(initialStock);
        }
    }
}
