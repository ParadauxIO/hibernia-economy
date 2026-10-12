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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E coverage of the ChestShop → Treasury sales-tax pipeline.
 *
 * <p>ChestShop's legacy {@code TaxModule} mutated the
 * {@link com.Acrobot.ChestShop.Events.Economy.CurrencyTransferEvent} amounts
 * and fired its own {@code CurrencyAddEvent} into a configured server
 * economy account. The Treasury-native fork now routes sales tax through
 * {@link net.democracycraft.treasury.api.TaxApi#collectRateTax} into
 * Treasury's default tax account (typically {@code DCGovernment}); the
 * legacy path is disabled when Treasury is loaded.
 *
 * <p>Two scenarios:
 * <ol>
 *   <li>Personal shop: buyer debited full price, seller credited net of
 *       tax, DCGovernment credited the tax.</li>
 *   <li>Business shop ({@code B:&lt;id&gt;} sign): same shape but the
 *       firm's BUSINESS account replaces "seller PERSONAL" on the credit
 *       side.</li>
 * </ol>
 *
 * <p>Tax rate is set via {@code /diag chestshoptax} (mutates
 * {@code Properties.TAX_AMOUNT} in the running plugin); reset to 0 in
 * {@link #resetTaxRate} so the rate doesn't bleed into subsequent test
 * classes.
 */
class SalesTaxTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final BigDecimal STARTING = new BigDecimal("10000");

    @AfterEach
    void resetTaxRate() {
        // Always restore — even on test failure — so other ChestShop tests
        // that assume tax = 0% don't see the carryover.
        RconClient.send("diag chestshoptax 0");
    }

    @Test
    void personalShopTradeRoutesTaxToDcGovernment() {
        String seller = Usernames.fresh("Tsl");
        String buyer = Usernames.fresh("Tby");
        int qty = 5;
        int price = 100;
        BigDecimal taxRatePct = new BigDecimal("10");
        BigDecimal expectedTax = BigDecimal.valueOf(price)
                .multiply(taxRatePct)
                .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP); // 10.00

        BigDecimal govBefore = Ledger.governmentBalance("DCGovernment").orElse(BigDecimal.ZERO);
        RconClient.send("diag chestshoptax 10");

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            shop.setupSellShop(seller, qty, price, "dirt");
            int initialChestStock = Inv.chestCount(shop.chestPos(), "DIRT");
            RconClient.send("diag warmname " + seller);
            shop.clickBuy(b, buyer);

            // Buyer paid the full price (no buy-side tax).
            Ledger.waitForPersonalBalance(b.getUuid(),
                    STARTING.subtract(BigDecimal.valueOf(price)), TIMEOUT);
            // Seller netted price minus tax: $100 in, $10 tax out → +$90.
            BigDecimal expectedSellerBalance = STARTING.add(BigDecimal.valueOf(price)).subtract(expectedTax);
            Ledger.waitForPersonalBalance(s.getUuid(), expectedSellerBalance, TIMEOUT);
            // DCGovernment credited the tax.
            BigDecimal expectedGov = govBefore.add(expectedTax);
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            BigDecimal lastGov = govBefore;
            while (System.nanoTime() < deadline) {
                lastGov = Ledger.governmentBalance("DCGovernment").orElse(BigDecimal.ZERO);
                if (lastGov.compareTo(expectedGov) == 0) break;
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            assertThat(lastGov)
                    .as("DCGovernment must receive the sales tax (was %s, expected %s)",
                            govBefore, expectedGov)
                    .isEqualByComparingTo(expectedGov);

            // Items still moved; tax is orthogonal to the goods leg.
            Inv.waitForChestCount(shop.chestPos(), "DIRT", initialChestStock - qty, TIMEOUT);
            Inv.waitForPlayerCount(buyer, "DIRT", qty, TIMEOUT);
        }
    }

    @Test
    void businessShopTradeTaxesFirmAccount() {
        String proprietor = Usernames.fresh("Tbp");
        String buyer = Usernames.fresh("Tbb");
        String firm = "Tsbf" + Long.toHexString(Instant.now().toEpochMilli());
        int qty = 5;
        int price = 100;
        BigDecimal expectedTax = new BigDecimal("10.00");

        BigDecimal govBefore = Ledger.governmentBalance("DCGovernment").orElse(BigDecimal.ZERO);
        RconClient.send("diag chestshoptax 10");

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
            int firmAccountId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
            String signOwner = "B:" + Integer.toString(firmAccountId, 36).toUpperCase();
            BigDecimal firmBefore = BusinessLedger.firmDefaultBalance(firmId).orElse(BigDecimal.ZERO);

            shop.setupSellShop(signOwner, qty, price, "dirt");
            shop.clickBuy(b, buyer);

            // Buyer's PERSONAL: -$100. Firm BUSINESS: +$100 - $10 = +$90.
            Ledger.waitForPersonalBalance(b.getUuid(),
                    STARTING.subtract(BigDecimal.valueOf(price)), TIMEOUT);
            BigDecimal expectedFirm = firmBefore.add(BigDecimal.valueOf(price)).subtract(expectedTax);
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            BigDecimal lastFirm = firmBefore;
            while (System.nanoTime() < deadline) {
                lastFirm = BusinessLedger.firmDefaultBalance(firmId).orElse(BigDecimal.ZERO);
                if (lastFirm.compareTo(expectedFirm) == 0) break;
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            assertThat(lastFirm).isEqualByComparingTo(expectedFirm);
            // DCGovernment got the tax — same expected delta as personal case.
            BigDecimal expectedGov = govBefore.add(expectedTax);
            BigDecimal lastGov = govBefore;
            deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                lastGov = Ledger.governmentBalance("DCGovernment").orElse(BigDecimal.ZERO);
                if (lastGov.compareTo(expectedGov) == 0) break;
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            assertThat(lastGov).isEqualByComparingTo(expectedGov);
        }
    }
}
