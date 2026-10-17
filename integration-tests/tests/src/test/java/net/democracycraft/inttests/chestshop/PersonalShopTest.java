package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.BlockFace;
import io.paradaux.mockclient.MockPlayer;
import io.paradaux.mockclient.Position;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Inv;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * End-to-end coverage of the personal-shop path through ChestShop's Treasury
 * integration: a sign owned by a regular player; the buyer's right-click
 * debits their PERSONAL account and credits the seller's PERSONAL account.
 *
 * <p>Prerequisites this test handles:
 * <ul>
 *   <li>{@link ChestShopFixture} places a stocked chest + sign with the
 *       correct 1.21+ NBT (plain string tags in {@code messages}, not JSON).</li>
 *   <li>{@code /diag warmname <seller>} forces ChestShop's NameManager to
 *       register the seller. ChestShop's {@code AccountQueryEvent} fires with
 *       {@code searchOfflinePlayers=false} from {@code PlayerInteract}, and
 *       its {@code PlayerConnect} listener only updates accounts that already
 *       exist — fresh joins never get a row, so a click on a never-warmed
 *       seller's sign returns "Player not found".</li>
 *   <li>Adventure mode for the buyer — ChestShop's {@code CreativeModeIgnorer}
 *       cancels every trade in creative when {@code IGNORE_CREATIVE_MODE} is
 *       on (the default).</li>
 *   <li>Teleport, then a client-driven position update + ~3 s for chunks to
 *       sync to the buyer's view-distance before the click.</li>
 *   <li>The chat-receipt check is unreliable (ChestShop's
 *       {@code SHOWITEM_MESSAGE} path renders via hover-event components that
 *       the mock's plain-text capture flattens differently). The ledger is
 *       the ground truth.</li>
 * </ul>
 */
class PersonalShopTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");

    @Test
    void buyerRightClickTransfersFundsBetweenPersonalAccounts() {
        String seller = Usernames.fresh("Slr");
        String buyer = Usernames.fresh("Byr");
        int qty = 5;
        int price = 100;

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            shop.setupSellShop(seller, qty, price, "dirt");
            int initialChestStock = Inv.chestCount(shop.chestPos(), "DIRT");
            assertThat(initialChestStock).isGreaterThanOrEqualTo(qty);
            assertThat(Inv.playerCount(buyer, "DIRT")).isZero();

            shop.clickBuy(b, buyer);

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING.add(BigDecimal.valueOf(price)), TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING.subtract(BigDecimal.valueOf(price)), TIMEOUT);

            // Items moved too: chest down by qty, buyer up by qty.
            Inv.waitForChestCount(shop.chestPos(), "DIRT", initialChestStock - qty, TIMEOUT);
            Inv.waitForPlayerCount(buyer, "DIRT", qty, TIMEOUT);
        }
    }
}
