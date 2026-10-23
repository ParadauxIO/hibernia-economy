package net.democracycraft.inttests.chestshop;

import static org.assertj.core.api.Assertions.assertThat;

import io.paradaux.mockclient.BlockFace;
import io.paradaux.mockclient.MockPlayer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import net.democracycraft.inttests.support.*;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.*;
import org.junit.jupiter.api.Test;

/** Real protocol clicks through Curator, asserting authoritative inventory and Treasury balances. */
class PurchasePreviewE2ETest {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final BigDecimal STARTING = new BigDecimal("10000");

    @Test
    void closingPreviewLeavesItemsAndMoneyUntouched() {
        try (var seller = MockPlayer.connectLocal(Usernames.fresh("PrSlr"));
             var buyer = MockPlayer.connectLocal(Usernames.fresh("PrByr"));
             var shop = ChestShopFixture.create()) {
            shop.setupSellShop(seller.getUsername(), 2, 10, "dirt");
            Ledger.waitForPersonalBalance(buyer.getUuid(), STARTING, TIMEOUT);
            int initial = Inv.chestCount(shop.chestPos(), "DIRT");
            shop.positionClicker(buyer, buyer.getUsername());
            var screen = buyer.awaitClientboundAfter(() -> buyer.useBlock(shop.signPos(), BlockFace.SOUTH),
                    ClientboundOpenScreenPacket.class, p -> true, TIMEOUT);
            Barrier.sync(buyer);
            assertThat(Inv.playerCount(buyer.getUsername(), "DIRT")).isZero();
            assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow()).isEqualByComparingTo(STARTING);
            buyer.sendPacket(new ServerboundContainerClosePacket(screen.getContainerId()));
            Barrier.settle(buyer);
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(initial);
            assertThat(Inv.playerCount(buyer.getUsername(), "DIRT")).isZero();
            assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow()).isEqualByComparingTo(STARTING);
        }
    }

    @Test
    void wildcardSelectionThenConfirmationTransfersThePreviewedBatch() {
        try (var seller = MockPlayer.connectLocal(Usernames.fresh("WcSlr"));
             var buyer = MockPlayer.connectLocal(Usernames.fresh("WcByr"));
             var shop = ChestShopFixture.create()) {
            shop.setupSellShop(seller.getUsername(), 2, 10, "dirt");
            RconClient.send(String.format("data modify block %d %d %d front_text.messages[3] set value 'dirt*'",
                    shop.signPos().x(), shop.signPos().y(), shop.signPos().z()));
            Ledger.waitForPersonalBalance(buyer.getUuid(), STARTING, TIMEOUT);
            shop.positionClicker(buyer, buyer.getUsername());
            var choices = buyer.awaitClientboundAfter(() -> buyer.useBlock(shop.signPos(), BlockFace.SOUTH),
                    ClientboundOpenScreenPacket.class, p -> true, TIMEOUT);
            var contents = Await.until("wildcard choices", () -> buyer.containerContent(choices.getContainerId()).orElse(null),
                    p -> p != null, TIMEOUT);
            buyer.awaitClientboundAfter(() -> buyer.sendPacket(new ServerboundContainerClickPacket(
                    choices.getContainerId(), contents.getStateId(), 0, ContainerActionType.CLICK_ITEM,
                    ClickItemAction.LEFT_CLICK, null, Map.of())), ClientboundOpenScreenPacket.class, p -> true, TIMEOUT);
            Barrier.sync(buyer);
            assertThat(Inv.playerCount(buyer.getUsername(), "DIRT")).isZero();
            assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow()).isEqualByComparingTo(STARTING);
            shop.confirmPurchase(buyer);
            Inv.waitForPlayerCount(buyer.getUsername(), "DIRT", 2, TIMEOUT);
            Ledger.waitForPersonalBalance(buyer.getUuid(), STARTING.subtract(BigDecimal.TEN), TIMEOUT);
            Ledger.waitForPersonalBalance(seller.getUuid(), STARTING.add(BigDecimal.TEN), TIMEOUT);
        }
    }
}
