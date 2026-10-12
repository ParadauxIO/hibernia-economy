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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage of the business-shop path: sign owner is the
 * {@code B:<base36>} token where the suffix decodes to the firm's
 * {@code BUSINESS} Treasury account id. A buyer's right-click should debit
 * their PERSONAL and credit the firm's BUSINESS — not the proprietor's
 * personal balance.
 *
 * <p>The base36 encoding of the firm's default account id is verbatim what
 * ChestShop's {@code TreasuryListener} decodes, so the assertion against
 * {@code BusinessLedger.firmDefaultBalance} confirms the full round-trip:
 * sign → decode → resolveAccountId → transfer → materialised balance.
 *
 * <p>No NameManager warm-up is needed for {@code B:<id>} signs — Treasury's
 * listener resolves the account directly from the base36 suffix.
 */
class BusinessShopTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final BigDecimal STARTING = new BigDecimal("10000");

    @Test
    void buyerRightClickCreditsFirmAccountNotProprietorPersonal() {
        String proprietor = Usernames.fresh("PrO");
        String buyer = Usernames.fresh("Byr");
        String firm = "Csb" + Long.toHexString(Instant.now().toEpochMilli());
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

            BigDecimal firmBefore = BusinessLedger.firmDefaultBalance(firmId).orElse(BigDecimal.ZERO);
            shop.setupSellShop(signOwner, qty, price, "dirt");
            int initialChestStock = Inv.chestCount(shop.chestPos(), "DIRT");
            assertThat(initialChestStock).isGreaterThanOrEqualTo(qty);
            assertThat(Inv.playerCount(buyer, "DIRT")).isZero();

            shop.clickBuy(b, buyer);

            // Assertions:
            // 1. Buyer's PERSONAL went down by `price`.
            // 2. Firm's BUSINESS account went up by `price`.
            // 3. Proprietor's PERSONAL is unchanged — proves the route went
            //    through the B:<id> path, not a fallback to proprietor personal.
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING.subtract(BigDecimal.valueOf(price)), TIMEOUT);
            waitForFirmDefaultBalance(firmId, firmBefore.add(BigDecimal.valueOf(price)), TIMEOUT);
            BigDecimal proprietorAfter = Ledger.personalBalance(p.getUuid())
                    .orElseThrow(() -> new AssertionError("proprietor balance missing"));
            assertThat(proprietorAfter).isEqualByComparingTo(STARTING);

            // Items moved: chest down by qty, buyer up by qty.
            Inv.waitForChestCount(shop.chestPos(), "DIRT", initialChestStock - qty, TIMEOUT);
            Inv.waitForPlayerCount(buyer, "DIRT", qty, TIMEOUT);

            // Confirm exactly one credit posting hit the firm account.
            assertThat(numTransfersInto(firmAccountId)).isEqualTo(1);
        }
    }

    private static void waitForFirmDefaultBalance(int firmId, BigDecimal expected, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        BigDecimal last = null;
        while (System.nanoTime() < deadline) {
            last = BusinessLedger.firmDefaultBalance(firmId).orElse(null);
            if (last != null && last.compareTo(expected) == 0) return;
            try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        throw new AssertionError("Firm " + firmId + " default balance did not reach "
                + expected + " within " + timeout + " (last seen: " + last + ")");
    }

    private static int numTransfersInto(int accountId) {
        try (Connection c = DriverManager.getConnection(
                net.democracycraft.inttests.support.DevServer.DB_URL,
                net.democracycraft.inttests.support.DevServer.DB_USER,
                net.democracycraft.inttests.support.DevServer.DB_PASSWORD);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM ledger_postings WHERE account_id = ? AND amount > 0")) {
            ps.setInt(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
