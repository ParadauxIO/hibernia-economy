package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import io.paradaux.mockclient.Position;
import net.democracycraft.inttests.support.Barrier;
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
 * Sell-direction coverage: the clicker is selling items <i>to</i> the shop.
 * Sign price line is {@code "<price> S"}; the click is a left-click; the
 * chest gains the items; the clicker gains the money.
 *
 * <p>One personal-shop variant (owner is a regular player; their PERSONAL
 * pays the seller) and one business-shop variant ({@code B:<id>} sign; the
 * firm's BUSINESS account pays).
 *
 * <p>The shop must be funded — ChestShop won't pay out from a $0 owner.
 * Personal: owner starts with the join-event $10,000, plenty. Business:
 * we fund the firm via {@code /business deposit}.
 */
class SellDirectionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");

    @Test
    void leftClickOnSellShopMovesItemsToChestAndMoneyToClient() {
        String owner = Usernames.fresh("Own");
        String client = Usernames.fresh("Cli");
        int qty = 5;
        int price = 50;

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer c = MockPlayer.connectLocal(client);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(o.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(c.getUuid(), STARTING, TIMEOUT);

            shop.setupBuyShop(owner, qty, price, "dirt");
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isZero();

            // The client must have items to sell. Hand them exactly qty.
            Inv.give(client, "DIRT", qty);
            Inv.waitForPlayerCount(client, "DIRT", qty, TIMEOUT);

            RconClient.send("diag warmname " + owner);

            // Left-click the sign — that's "sell to shop" in the default config.
            shop.clickSell(c, client);

            // Money: owner -price, client +price.
            Ledger.waitForPersonalBalance(o.getUuid(), STARTING.subtract(BigDecimal.valueOf(price)), TIMEOUT);
            Ledger.waitForPersonalBalance(c.getUuid(), STARTING.add(BigDecimal.valueOf(price)), TIMEOUT);
            // Items: client's qty dirt -> chest.
            Inv.waitForChestCount(shop.chestPos(), "DIRT", qty, TIMEOUT);
            Inv.waitForPlayerCount(client, "DIRT", 0, TIMEOUT);
        }
    }

    @Test
    void leftClickOnBusinessSellShopDebitsFirmAndCreditsClient() {
        String proprietor = Usernames.fresh("Pro");
        String client = Usernames.fresh("Cli");
        String firm = "Sdb" + Long.toHexString(Instant.now().toEpochMilli());
        int qty = 5;
        int price = 50;
        BigDecimal firmFund = new BigDecimal("500");

        try (MockPlayer p = MockPlayer.connectLocal(proprietor);
             MockPlayer c = MockPlayer.connectLocal(client);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(p.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(c.getUuid(), STARTING, TIMEOUT);

            int firmId;
            int firmAccountId;
            // Create the firm and fund the default account so it can pay out.
            Ops.run(proprietor, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                Barrier.settle();
                p.chatCommand("/business deposit " + firm + " " + firmFund);
                p.awaitMessageContaining("Paid", TIMEOUT);
            });
            firmId = BusinessLedger.firmId(firm).orElseThrow();
            firmAccountId = BusinessLedger.firmDefaultAccountId(firmId)
                    .orElseThrow(() -> new AssertionError("firm has no default account"));
            String signOwner = "B:" + Integer.toString(firmAccountId, 36).toUpperCase();

            // Owner deposit reduced their personal by firmFund.
            BigDecimal proprietorAfterDeposit = Ledger.personalBalance(p.getUuid()).orElseThrow();

            shop.setupBuyShop(signOwner, qty, price, "dirt");
            Inv.give(client, "DIRT", qty);
            Inv.waitForPlayerCount(client, "DIRT", qty, TIMEOUT);

            shop.clickSell(c, client);

            // Firm account paid out; client got paid; proprietor unchanged.
            BusinessLedger.waitForFirmDefaultBalance(firmId, firmFund.subtract(BigDecimal.valueOf(price)), TIMEOUT);
            Ledger.waitForPersonalBalance(c.getUuid(), STARTING.add(BigDecimal.valueOf(price)), TIMEOUT);
            assertThat(Ledger.personalBalance(p.getUuid()).orElseThrow())
                    .isEqualByComparingTo(proprietorAfterDeposit);
            // Items moved.
            Inv.waitForChestCount(shop.chestPos(), "DIRT", qty, TIMEOUT);
            Inv.waitForPlayerCount(client, "DIRT", 0, TIMEOUT);
        }
    }
}
