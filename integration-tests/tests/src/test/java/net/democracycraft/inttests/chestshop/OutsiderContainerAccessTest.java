package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.BlockFace;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class OutsiderContainerAccessTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void ownerOpensStockButOutsiderReceivesOnlyReadOnlyShopInformation() {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Own"));
             MockPlayer outsider = MockPlayer.connectLocal(Usernames.fresh("Out"));
             ChestShopFixture shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(outsider.getUuid(), TIMEOUT);
            shop.setupSellShop(owner.getUsername(), 3, 37, "dirt");
            RconClient.send("diag warmname " + owner.getUsername());
            shop.positionClicker(owner, owner.getUsername());
            var opened = owner.collectClientboundDuring(
                    () -> owner.useBlock(shop.chestPos(), BlockFace.SOUTH), ClientboundOpenScreenPacket.class, TIMEOUT);
            assertThat(opened).hasSize(1);
            owner.closeContainer(opened.get(0).getContainerId());
            Barrier.sync(owner);
            var p = shop.signPos();
            var before = Market.waitForShopAt("world", p.x(), p.y(), p.z(), TIMEOUT);
            shop.positionClicker(outsider, outsider.getUsername());
            Perms.grant(outsider.getUsername(), "chestshop.shopinfo");
            var denied = outsider.collectClientboundDuring(
                    () -> outsider.useBlock(shop.chestPos(), BlockFace.SOUTH), ClientboundOpenScreenPacket.class, TIMEOUT);
            assertThat(denied).isEmpty();
            assertThat(outsider.awaitMessageContaining("Shop Information", TIMEOUT))
                    .contains(owner.getUsername(), "Stock: 36", "Dirt");
            Barrier.settle();
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(36);
            assertThat(Inv.playerCount(outsider.getUsername(), "DIRT")).isZero();
            assertThat(Market.shopAt("world", p.x(), p.y(), p.z()).orElseThrow()).isEqualTo(before);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(outsider.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Market.saleCountForCustomer(outsider.getUuid())).isZero();
        }
    }
}
