package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class InformationCommandsTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @ParameterizedTest
    @ValueSource(strings = {"shopinfo", "sinfo", "shop"})
    void shopInformationReportsActualOwnerStockAndTerms(String command) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Info"));
             ChestShopFixture shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Perms.grant(owner.getUsername(), "chestshop.shopinfo");
            shop.setupSellShop(owner.getUsername(), 3, 37, "dirt");
            RconClient.send("diag warmname " + owner.getUsername());
            shop.positionClicker(owner, owner.getUsername());
            owner.chatCommand("/" + command);
            String info = owner.awaitMessageContaining("Shop Information", TIMEOUT);
            assertThat(info).contains("Owner: " + owner.getUsername(), "Stock: 36", "Dirt");
            assertThat(owner.awaitMessageContaining("Buy", TIMEOUT)).contains("3", "37");
            Barrier.settle();
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(36);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"iteminfo", "iinfo"})
    void explicitItemCodeReportsCanonicalSignName(String command) {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Info"))) {
            Perms.grant(player.getUsername(), "chestshop.iteminfo");
            player.chatCommand("/" + command + " redstone_block");
            assertThat(player.awaitMessageContaining("Shop Sign:", TIMEOUT)).contains("Redstone Block");
            Barrier.settle();
            assertThat(Inv.playerCount(player.getUsername(), "REDSTONE_BLOCK")).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"iteminfo", "iinfo"})
    void heldItemRouteUsesSelectedHotbarItem(String command) {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Info"))) {
            Perms.grant(player.getUsername(), "chestshop.iteminfo");
            RconClient.send("item replace entity " + player.getUsername() + " hotbar.2 with minecraft:diamond 4");
            player.selectHotbarSlot(2);
            Barrier.sync(player);
            player.chatCommand("/" + command);
            assertThat(player.awaitMessageContaining("Shop Sign:", TIMEOUT)).contains("Diamond");
            assertThat(Inv.playerCount(player.getUsername(), "DIAMOND")).isEqualTo(4);
        }
    }
}
