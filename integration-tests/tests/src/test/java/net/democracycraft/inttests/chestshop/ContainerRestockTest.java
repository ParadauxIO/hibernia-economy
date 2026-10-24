package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.BlockFace;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.geysermc.mcprotocollib.protocol.data.game.item.HashedStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class ContainerRestockTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void ownerMovesItemsThroughChestUiAndClosingRefreshesRegistryWithoutPayment() {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Inv"));
             ChestShopFixture shop = ChestShopFixture.create()) {
            String name = owner.getUsername();
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            shop.setupSellShop(name, 3, 37, "dirt");
            RconClient.send("diag warmname " + name);
            shop.positionClicker(owner, name);
            owner.chatCommand("/cs toggle visibility true");
            owner.awaitMessageContaining("now shown", TIMEOUT);
            Barrier.settle();
            long id = row(shop).shopId();
            assertThat(row(shop).currentStock()).isEqualTo(36);
            RconClient.send("item replace entity " + name + " hotbar.0 with minecraft:dirt 5");
            var deposit = open(owner, shop);
            assertThat(deposit.getItems()).hasSize(63);
            assertThat(deposit.getItems()[0].getAmount()).isEqualTo(36);
            assertThat(deposit.getItems()[54].getAmount()).isEqualTo(5);
            int dirt = deposit.getItems()[0].getId();
            assertThat(deposit.getItems()[54].getId()).isEqualTo(dirt);
            owner.quickMoveContainerSlot(deposit.getContainerId(), deposit.getStateId(), 54,
                    prediction(54, 0, dirt, 41));
            Barrier.sync(owner);
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(41);
            assertThat(Inv.playerCount(name, "DIRT")).isZero();
            owner.closeContainer(deposit.getContainerId());
            Barrier.sync(owner);
            Await.until("closed chest stock refreshed to 41", () -> row(shop).currentStock(), n -> n == 41, TIMEOUT);

            var withdraw = open(owner, shop);
            assertThat(withdraw.getItems()[0].getAmount()).isEqualTo(41);
            // Vanilla chest quick-move fills the player's hotbar from its last slot.
            assertThat(withdraw.getItems()[62]).isNull();
            owner.quickMoveContainerSlot(withdraw.getContainerId(), withdraw.getStateId(), 0,
                    prediction(0, 62, dirt, 41));
            Barrier.sync(owner);
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isZero();
            assertThat(Inv.playerCount(name, "DIRT")).isEqualTo(41);
            owner.closeContainer(withdraw.getContainerId());
            Barrier.sync(owner);
            Await.until("closed chest stock refreshed to zero", () -> row(shop).currentStock(), n -> n == 0, TIMEOUT);
            assertThat(row(shop).shopId()).isEqualTo(id);
            assertThat(row(shop).buyPrice()).isEqualByComparingTo("37");
            assertThat(row(shop).batchQty()).isEqualTo(3);
            assertThat(row(shop).active()).isTrue();
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Market.saleCountForCustomer(owner.getUuid())).isZero();
        }
    }

    private static ClientboundContainerSetContentPacket open(MockPlayer owner, ChestShopFixture shop) {
        return owner.awaitClientboundAfter(() -> owner.useBlock(shop.chestPos(), BlockFace.SOUTH),
                ClientboundContainerSetContentPacket.class, p -> p.getContainerId() != 0, TIMEOUT);
    }

    private static Map<Integer, HashedStack> prediction(int source, int destination, int item, int amount) {
        Map<Integer, HashedStack> changed = new HashMap<>();
        changed.put(source, null);
        changed.put(destination, new HashedStack(item, amount, Map.of(), Set.of()));
        return changed;
    }

    private static Market.Shop row(ChestShopFixture shop) {
        var p = shop.signPos();
        return Market.waitForShopAt("world", p.x(), p.y(), p.z(), TIMEOUT);
    }
}
