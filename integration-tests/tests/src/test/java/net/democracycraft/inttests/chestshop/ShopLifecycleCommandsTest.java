package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** RCON prepares blocks; player commands and digging packets drive the behavior under test. */
class ShopLifecycleCommandsTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @ParameterizedTest
    @ValueSource(strings = {"chestshop", "find", "csdb find"})
    void ownerCanHideAndShowShopWithoutChangingItsTradeTerms(String root) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Shop"));
             ChestShopFixture shop = ChestShopFixture.create()) {
            prepare(owner, shop);
            Perms.grant(owner.getUsername(), "chestshop.find");
            toggle(owner, root, "visibility", false);
            Market.Shop hidden = row(shop);
            assertThat(hidden.active()).isTrue();
            assertThat(hidden.visible()).isFalse();
            assertThat(hidden.buyPrice()).isEqualByComparingTo("37");
            assertThat(hidden.batchQty()).isEqualTo(3);
            assertThat(hidden.shopOwnerUuid()).isEqualTo(owner.getUuid());
            toggle(owner, root, "visibility", true);
            Market.Shop shown = row(shop);
            assertThat(shown.visible()).isTrue();
            assertThat(shown.shopId()).isEqualTo(hidden.shopId());
            assertThat(shown.buyPrice()).isEqualByComparingTo(hidden.buyPrice());
            assertThat(shown.batchQty()).isEqualTo(hidden.batchQty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cs", "find", "csdb find"})
    void nonOwnerCannotChangeVisibilityOrHologram(String root) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Shop"));
             MockPlayer visitor = MockPlayer.connectLocal(Usernames.fresh("Visit"));
             ChestShopFixture shop = ChestShopFixture.create()) {
            prepare(owner, shop);
            toggle(owner, "visibility", true);
            toggle(owner, "hologram", true);
            Market.Shop before = row(shop);
            shop.positionClicker(visitor, visitor.getUsername());
            Perms.grant(visitor.getUsername(), "chestshop.find");
            for (String property : new String[]{"visibility", "hologram"}) {
                visitor.chatCommand("/" + root + " toggle " + property + " false");
                visitor.awaitMessageContaining("isn't your shop", TIMEOUT);
                Barrier.settle();
                assertThat(row(shop)).isEqualTo(before);
            }
        }
    }

    @Test
    void ownerHologramPreferencePersistsAndDiggingDeactivatesRegistryEntry() {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Shop"));
             ChestShopFixture shop = ChestShopFixture.create()) {
            prepare(owner, shop);
            toggle(owner, "hologram", false);
            assertThat(row(shop).hologram()).isFalse();
            toggle(owner, "hologram", true);
            assertThat(row(shop).hologram()).isTrue();
            RconClient.send("gamemode creative " + owner.getUsername());
            Barrier.sync(owner);
            owner.breakBlock(shop.signPos());
            Barrier.sync(owner);
            Await.until("shop sign removed by digging packet", () -> RconClient.send("diag checksign world "
                    + shop.signPos().x() + " " + shop.signPos().y() + " " + shop.signPos().z()),
                    response -> response.contains("type=AIR"), TIMEOUT);
            Await.until("broken shop deactivated", () -> row(shop), state -> !state.active(), TIMEOUT);
        }
    }

    @Test
    void adminBypassOptOutEnforcesOwnershipAndCanBeRestored() {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Shop"));
             MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("Adm"));
             ChestShopFixture shop = ChestShopFixture.create()) {
            prepare(owner, shop);
            Perms.grant(admin.getUsername(), "chestshop.admin");
            shop.positionClicker(admin, admin.getUsername());
            toggle(admin, "visibility", false);
            Market.Shop hidden = row(shop);
            assertThat(hidden.visible()).isFalse();

            admin.chatCommand("/cs bypass");
            admin.awaitMessageContaining("bypass disabled", TIMEOUT);
            admin.chatCommand("/cs toggle visibility true");
            admin.awaitMessageContaining("isn't your shop", TIMEOUT);
            Barrier.settle();
            assertThat(row(shop)).isEqualTo(hidden);

            admin.chatCommand("/chestshop bypass");
            admin.awaitMessageContaining("bypass restored", TIMEOUT);
            toggle(admin, "visibility", true);
            Market.Shop restored = row(shop);
            assertThat(restored.visible()).isTrue();
            assertThat(restored.shopId()).isEqualTo(hidden.shopId());
            assertThat(restored.shopOwnerUuid()).isEqualTo(owner.getUuid());
            assertThat(restored.buyPrice()).isEqualByComparingTo(hidden.buyPrice());
        }
    }

    private static void prepare(MockPlayer owner, ChestShopFixture shop) {
        Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
        shop.setupSellShop(owner.getUsername(), 3, 37, "dirt");
        RconClient.send("diag warmname " + owner.getUsername());
        shop.positionClicker(owner, owner.getUsername());
    }

    private static void toggle(MockPlayer owner, String property, boolean value) {
        toggle(owner, "chestshop", property, value);
    }

    private static void toggle(MockPlayer owner, String root, String property, boolean value) {
        owner.chatCommand("/" + root + " toggle " + property + " " + value);
        owner.awaitMessageContaining("now", TIMEOUT);
        Barrier.settle();
    }

    private static Market.Shop row(ChestShopFixture shop) {
        var pos = shop.signPos();
        return Market.waitForShopAt("world", pos.x(), pos.y(), pos.z(), TIMEOUT);
    }
}
