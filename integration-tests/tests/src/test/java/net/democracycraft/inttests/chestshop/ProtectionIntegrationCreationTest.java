package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WorldGuard + GriefPrevention creation gating, exercised together. ChestShop's
 * integration hooks are registered at <b>startup</b> from config (Dependencies.java),
 * so they can't be flipped on at runtime like other config — this test requires the
 * server booted with {@code WORLDGUARD_INTEGRATION: true} and
 * {@code GRIEFPREVENTION_INTEGRATION: true} in ChestShop's config.yml. It's therefore
 * gated behind {@code -Dchestshop.integrations=true} and excluded from the default
 * green run; a dedicated run with those configs on proves both gates enforce.
 *
 * <p>With both on, creation is denied unless BOTH a WorldGuard region (WG, USE_FLAG off →
 * "some region must exist") AND a GriefPrevention claim (GP → "a claim must exist") cover
 * the sign. The four cases isolate each gate:
 * <ul>
 *   <li>wilderness (neither) → denied,</li>
 *   <li>region only (no claim) → denied by GriefPrevention,</li>
 *   <li>claim only (no region) → denied by WorldGuard,</li>
 *   <li>region + claim → allowed.</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "chestshop.integrations", matches = "true")
class ProtectionIntegrationCreationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int QTY = 5;
    private static final int PRICE = 100;

    @BeforeEach
    void clearProtectionState() {
        // WG regions + GP claims persist to disk and are placed at the fixed fixture
        // coordinates, so clear them first or they overlap/accumulate across runs.
        RconClient.send("diag protclear world");
    }

    private static void regionAround(ChestShopFixture shop) {
        RconClient.send(String.format("diag wgregion world rg%d %d %d %d %d %d %d",
                shop.signPos().x(),
                shop.chestPos().x() - 2, shop.chestPos().y() - 3, shop.chestPos().z() - 2,
                shop.chestPos().x() + 2, shop.chestPos().y() + 3, shop.chestPos().z() + 2));
    }

    private static void claimAround(ChestShopFixture shop, String owner) {
        RconClient.send(String.format("diag gpclaim world %d %d %d %d %s",
                shop.chestPos().x() - 2, shop.chestPos().z() - 2,
                shop.chestPos().x() + 2, shop.chestPos().z() + 2, owner));
    }

    private String tryCreate(String seller, boolean region, boolean claim) {
        try (MockPlayer s = MockPlayer.connectLocal(seller);
             ChestShopFixture shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            if (region) regionAround(shop);
            if (claim) claimAround(shop, seller);
            return shop.createSellShop(seller, seller, QTY, PRICE, "dirt");
        }
    }

    @Test
    void wildernessDenied() {
        assertThat(tryCreate(Usernames.fresh("Pi1"), false, false))
                .as("no region and no claim → denied").contains("cancelled=true");
    }

    @Test
    void regionWithoutClaimDeniedByGriefPrevention() {
        assertThat(tryCreate(Usernames.fresh("Pi2"), true, false))
                .as("region but no claim → GriefPrevention denies").contains("cancelled=true");
    }

    @Test
    void claimWithoutRegionDeniedByWorldGuard() {
        assertThat(tryCreate(Usernames.fresh("Pi3"), false, true))
                .as("claim but no region → WorldGuard denies").contains("cancelled=true");
    }

    @Test
    void regionAndClaimAllowsCreation() {
        assertThat(tryCreate(Usernames.fresh("Pi4"), true, true))
                .as("region and claim → creation allowed").contains("cancelled=false");
    }
}
