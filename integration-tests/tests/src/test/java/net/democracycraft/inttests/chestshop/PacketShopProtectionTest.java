package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.BlockPos;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class PacketShopProtectionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void outsiderDiggingCannotDestroyShopAndNextPurchaseStillSettles(boolean targetSign) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Own"));
             MockPlayer visitor = MockPlayer.connectLocal(Usernames.fresh("Dig"));
             ChestShopFixture shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(visitor.getUuid(), TIMEOUT);
            shop.setupSellShop(owner.getUsername(), 3, 37, "dirt");
            RconClient.send("diag warmname " + owner.getUsername());
            shop.positionClicker(owner, owner.getUsername());
            owner.chatCommand("/cs toggle visibility true");
            owner.awaitMessageContaining("now shown", TIMEOUT);
            Barrier.settle();
            var before = row(shop);
            shop.positionClicker(visitor, visitor.getUsername());
            RconClient.send("gamemode creative " + visitor.getUsername());
            Barrier.sync(visitor);
            // Positive control: the client is close enough and can actually break blocks.
            var c = shop.chestPos();
            BlockPos control = BlockPos.of(c.x() + 1, c.y(), c.z() + 1);
            RconClient.send("setblock " + control.x() + " " + control.y() + " " + control.z() + " minecraft:stone");
            visitor.breakBlock(control);
            Barrier.sync(visitor);
            Await.until("control block broken", () -> blockType(control), r -> r.contains("type=AIR"), TIMEOUT);
            visitor.drainMessages();
            shop.digBlock(visitor, targetSign ? shop.signPos() : shop.chestPos());
            Barrier.sync(visitor);
            Barrier.settle();
            // The interaction listener cancels an outsider's sign/chest click
            // before BlockBreakEvent. Sign clicks also run trade validation;
            // chest clicks show read-only information to the default shopinfo role.
            // These replies prove dispatch; surviving blocks and the next trade
            // assert the gameplay contract, not a particular event callback.
            visitor.awaitMessageContaining(targetSign
                    ? "cannot trade with shops while you are in creative mode"
                    : "Shop Information", TIMEOUT);
            assertThat(blockType(shop.signPos())).contains("type=OAK_SIGN");
            assertThat(blockType(shop.chestPos())).contains("type=CHEST");
            assertThat(row(shop)).isEqualTo(before);
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(36);
            assertThat(Inv.playerCount(visitor.getUsername(), "DIRT")).isZero();
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(visitor.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            RconClient.send("gamemode survival " + visitor.getUsername());
            Barrier.sync(visitor);
            shop.clickBuy(visitor, visitor.getUsername());
            Barrier.settle();
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(33);
            assertThat(Inv.playerCount(visitor.getUsername(), "DIRT")).isEqualTo(3);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10037");
            assertThat(Ledger.personalBalance(visitor.getUuid()).orElseThrow()).isEqualByComparingTo("9963");
            assertThat(row(shop).active()).isTrue();
        }
    }

    private static String blockType(BlockPos pos) {
        return RconClient.send("diag checksign world " + pos.x() + " " + pos.y() + " " + pos.z());
    }

    private static Market.Shop row(ChestShopFixture shop) {
        var p = shop.signPos();
        return Market.waitForShopAt("world", p.x(), p.y(), p.z(), TIMEOUT);
    }
}
