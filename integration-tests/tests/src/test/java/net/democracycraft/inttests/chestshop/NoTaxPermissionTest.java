package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code ChestShop.notax.buy} permission exempts the trade from sales tax:
 * with a 10% rate set, a buyer holding the node pays the seller the full price
 * and DCGovernment receives nothing.
 */
class NoTaxPermissionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final BigDecimal STARTING = new BigDecimal("10000");

    @AfterEach
    void resetTaxRate() {
        RconClient.send("diag chestshoptax 0");
    }

    @Test
    void buyerWithNoTaxPermissionPaysNoTax() {
        String seller = Usernames.fresh("Nts");
        String buyer = Usernames.fresh("Ntb");
        int qty = 5;
        int price = 100;

        BigDecimal govBefore = Ledger.governmentBalance("DCGovernment").orElse(BigDecimal.ZERO);
        RconClient.send("diag chestshoptax 10");

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            shop.setupSellShop(seller, qty, price, "dirt");
            RconClient.send("lp user " + buyer + " permission set chestshop.notax.buy true");

            shop.clickBuy(b, buyer);

            // No tax: buyer -100, seller +100 (full), DCGovernment unchanged.
            Ledger.waitForPersonalBalance(b.getUuid(),
                    STARTING.subtract(BigDecimal.valueOf(price)), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(),
                    STARTING.add(BigDecimal.valueOf(price)), TIMEOUT);
            try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            assertThat(Ledger.governmentBalance("DCGovernment").orElse(BigDecimal.ZERO))
                    .as("no tax should reach DCGovernment when the buyer is exempt")
                    .isEqualByComparingTo(govBefore);
        }
    }
}
