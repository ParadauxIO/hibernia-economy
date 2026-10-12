package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.ChestShopFixture;
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
 * Built-in protection on a <b>business</b> ChestShop ({@code B:<accountId>} owner).
 * Breaking a business shop's sign is gated by the firm's {@code RolePermission.CHESTSHOP}
 * (via AccountService → EconomyService.canAccessBusinessAccount → BusinessApi):
 * the firm <b>proprietor</b> and any member whose role carries {@code CHESTSHOP}
 * may break it; non-members and ordinary employees may not. (Buying is a separate
 * path open to everyone — covered by the business-shop buy tests.)
 */
class BusinessShopProtectionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int QTY = 5;
    private static final int PRICE = 100;

    private static String breakSign(String player, ChestShopFixture shop) {
        return RconClient.send("diag break " + player + " world "
                + shop.signPos().x() + " " + shop.signPos().y() + " " + shop.signPos().z());
    }

    private static String signType(ChestShopFixture shop) {
        return RconClient.send("diag checksign world "
                + shop.signPos().x() + " " + shop.signPos().y() + " " + shop.signPos().z());
    }

    /** Retry the (non-destructive when denied) break until it's allowed — absorbs permission-cache lag. */
    private static void awaitBreakAllowed(String player, ChestShopFixture shop, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        String last = "";
        while (System.nanoTime() < deadline) {
            last = breakSign(player, shop);
            if (last.contains("cancelled=false")) return;
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("break never became allowed for " + player + " (last: " + last + ")");
    }

    private static int firmAccountShop(MockPlayer p, String proprietor, String firm, ChestShopFixture shop) {
        Ops.run(proprietor, () -> {
            p.chatCommand("/business create " + firm);
            p.awaitMessageContaining("Successfully created", TIMEOUT);
            BusinessLedger.waitForFirm(firm, TIMEOUT);
        });
        int firmId = BusinessLedger.firmId(firm).orElseThrow();
        // Make sure the proprietor relationship is committed before we test protection —
        // under suite load the isProprietor lookup can otherwise read ahead of the write.
        BusinessLedger.waitForFirmProprietor(firmId, p.getUuid(), TIMEOUT);
        int accId = BusinessLedger.firmDefaultAccountId(firmId)
                .orElseThrow(() -> new AssertionError("firm has no default account"));
        String owner = "B:" + Integer.toString(accId, 36).toUpperCase();
        shop.setupSellShop(owner, QTY, PRICE, "dirt");
        return firmId;
    }

    @Test
    void proprietorCanBreakButOutsiderCannot() {
        String proprietor = Usernames.fresh("Bpp");
        String outsider = Usernames.fresh("Bpx");
        String firm = "Bpf" + Long.toHexString(Instant.now().toEpochMilli());

        try (MockPlayer p = MockPlayer.connectLocal(proprietor);
             MockPlayer x = MockPlayer.connectLocal(outsider);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(p.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(x.getUuid(), STARTING, TIMEOUT);

            firmAccountShop(p, proprietor, firm, shop);

            // A non-member can't break the firm's shop.
            assertThat(breakSign(outsider, shop)).as("outsider denied").contains("cancelled=true");
            assertThat(signType(shop)).doesNotContain("type=AIR");

            // The proprietor can (retry — firm/account resolution can lag under suite load).
            awaitBreakAllowed(proprietor, shop, TIMEOUT);
            assertThat(signType(shop)).contains("type=AIR");
        }
    }

    @Test
    void employeeCanBreakOnlyWithChestshopPermission() {
        String proprietor = Usernames.fresh("Bep");
        String employee = Usernames.fresh("Bee");
        String firm = "Bef" + Long.toHexString(Instant.now().toEpochMilli());

        try (MockPlayer p = MockPlayer.connectLocal(proprietor);
             MockPlayer e = MockPlayer.connectLocal(employee);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(p.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(e.getUuid(), STARTING, TIMEOUT);

            int firmId = firmAccountShop(p, proprietor, firm, shop);

            // Hire the employee (joins on the default role, which lacks CHESTSHOP).
            Ops.run(proprietor, () -> {
                p.drainMessages();
                Ledger.asyncSettle();
                p.chatCommand("/business offer " + firm + " " + employee);
                p.awaitMessageContaining("job offer has sent", TIMEOUT);
            });
            Ops.run(employee, () -> {
                e.drainMessages();
                e.chatCommand("/business offer accept " + firm);
                e.awaitMessageContaining("accepted a job offer", TIMEOUT);
            });
            BusinessLedger.waitForEmployeeCount(firmId, 1, TIMEOUT);

            // Ordinary employee can't break the shop.
            assertThat(breakSign(employee, shop)).as("employee without CHESTSHOP denied").contains("cancelled=true");
            assertThat(signType(shop)).doesNotContain("type=AIR");

            // Grant CHESTSHOP to the employee's role.
            String role = BusinessLedger.currentEmployeeRoleName(firmId, e.getUuid()).orElseThrow();
            Ops.run(proprietor, () -> {
                p.drainMessages();
                Ledger.asyncSettle();
                p.chatCommand("/business staff role permission add " + firm + " " + role + " CHESTSHOP");
                p.awaitMessageContaining("permission CHESTSHOP has been added", TIMEOUT);
                Ledger.asyncSettle();
            });

            // Now the employee may break it.
            awaitBreakAllowed(employee, shop, TIMEOUT);
            assertThat(signType(shop)).contains("type=AIR");
        }
    }
}
