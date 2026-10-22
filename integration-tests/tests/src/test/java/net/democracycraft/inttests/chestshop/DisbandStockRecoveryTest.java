package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.BlockFace;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.geysermc.mcprotocollib.protocol.data.game.item.HashedStack;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class DisbandStockRecoveryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void formerProprietorRecoversStockThroughContainerAfterDisbandWithoutTrading() throws Exception {
        String firm = Usernames.fresh("RecF");
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("RecO"));
             var outsider = MockPlayer.connectLocal(Usernames.fresh("RecX"));
             var shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(outsider.getUuid(), TIMEOUT);
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business create " + firm);
                owner.awaitMessageContaining("Successfully created", TIMEOUT);
            });
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int account = BusinessLedger.firmDefaultAccountId(id).orElseThrow();
            Ops.run(owner.getUsername(), () -> assertThat(shop.createSellShop(owner.getUsername(),
                    "B:" + Integer.toString(account, 36).toUpperCase(), 3, 17, "DIRT")).contains("cancelled=false"));
            var pos = shop.signPos();
            var before = Market.waitForShopAt("world", pos.x(), pos.y(), pos.z(), TIMEOUT);
            shop.positionClicker(outsider, outsider.getUsername());
            var denied = outsider.collectClientboundDuring(
                    () -> outsider.useBlock(shop.chestPos(), BlockFace.SOUTH), ClientboundOpenScreenPacket.class, TIMEOUT);
            assertThat(denied).isEmpty();
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(36);
            Ops.run(owner.getUsername(), () -> {
                owner.chatCommand("/business disband " + firm);
                owner.awaitMessageContaining("is permanent", TIMEOUT);
                owner.chatCommand("/business disband " + firm + " confirm");
                owner.awaitMessageContaining("has been disbanded, and funds have been returned", TIMEOUT);
            });
            Barrier.settle();
            assertThat(BusinessLedger.firmIsArchived(firm)).isTrue();
            assertThat(BusinessLedger.activeFirmAccountCount(id)).isZero();
            long checkpoint = Ledger.transactionCheckpoint();
            // No op or permission elevation during recovery: this is the former proprietor.
            shop.positionClicker(owner, owner.getUsername());
            var opened = owner.awaitClientboundAfter(() -> owner.useBlock(shop.chestPos(), BlockFace.SOUTH),
                    ClientboundContainerSetContentPacket.class, packet -> packet.getContainerId() != 0, TIMEOUT);
            assertThat(opened.getItems()).hasSize(63);
            assertThat(opened.getItems()[0].getAmount()).isEqualTo(36);
            assertThat(opened.getItems()[62]).isNull();
            var prediction = new HashMap<Integer, HashedStack>();
            prediction.put(0, null);
            prediction.put(62, new HashedStack(opened.getItems()[0].getId(), 36, Map.of(), Set.of()));
            owner.quickMoveContainerSlot(opened.getContainerId(), opened.getStateId(), 0, prediction);
            Barrier.sync(owner);
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isZero();
            assertThat(Inv.playerCount(owner.getUsername(), "DIRT")).isEqualTo(36);
            owner.closeContainer(opened.getContainerId());
            Barrier.sync(owner);
            Await.until("recovered shop stock refresh", () -> Market.shopAt("world", pos.x(), pos.y(), pos.z()).orElseThrow().currentStock(),
                    stock -> stock != null && stock == 0, TIMEOUT);
            assertThat(Market.shopAt("world", pos.x(), pos.y(), pos.z()).orElseThrow().shopId()).isEqualTo(before.shopId());
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(outsider.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("0");
            assertThat(Inv.playerCount(outsider.getUsername(), "DIRT")).isZero();
            assertThat(Market.saleCountForCustomer(owner.getUuid())).isZero();
            assertThat(Market.saleCountForCustomer(outsider.getUuid())).isZero();
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }
}
