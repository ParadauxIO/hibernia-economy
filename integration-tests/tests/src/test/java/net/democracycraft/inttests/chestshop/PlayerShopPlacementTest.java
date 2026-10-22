package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.BlockFace;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundOpenSignEditorPacket;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerShopPlacementTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void playerPlacesEditsAndTradesAtShopThroughRealPackets() {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Place"));
             MockPlayer buyer = MockPlayer.connectLocal(Usernames.fresh("Buyer"));
             ChestShopFixture shop = ChestShopFixture.create()) {
            BigDecimal ownerBefore = Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            BigDecimal buyerBefore = Ledger.waitForPersonalBalance(buyer.getUuid(), TIMEOUT);
            var chest = shop.chestPos();
            RconClient.send("setblock " + chest.x() + " " + chest.y() + " " + chest.z() + " minecraft:chest");
            RconClient.send("setblock " + chest.x() + " " + (chest.y() - 1) + " " + (chest.z() + 1) + " minecraft:stone");
            RconClient.send("item replace block " + chest.x() + " " + chest.y() + " " + chest.z()
                    + " container.0 with minecraft:dirt 20");
            shop.positionClicker(owner, owner.getUsername());
            RconClient.send("gamemode survival " + owner.getUsername());
            Inv.clear(owner.getUsername());
            RconClient.send("item replace entity " + owner.getUsername() + " hotbar.0 with minecraft:oak_sign 1");
            owner.selectHotbarSlot(0);
            owner.setSneaking(true);
            Barrier.sync(owner);
            var editor = owner.awaitClientboundAfter(() -> owner.useBlock(chest, BlockFace.UP),
                    ClientboundOpenSignEditorPacket.class,
                    packet -> packet.getPosition().equals(shop.signPos().toVector3i()), TIMEOUT);
            owner.updateSign(shop.signPos(), new String[]{owner.getUsername(), "4", "25 B", "DIRT"}, editor.isFrontText());
            owner.setSneaking(false);
            Barrier.sync(owner);
            Market.Shop registered = Market.waitForShopAt("world", shop.signPos().x(), shop.signPos().y(), shop.signPos().z(), TIMEOUT);
            assertThat(registered.shopOwnerUuid()).isEqualTo(owner.getUuid());
            assertThat(registered.buyPrice()).isEqualByComparingTo("25");
            assertThat(registered.batchQty()).isEqualTo(4);
            assertThat(Inv.playerCount(owner.getUsername(), "OAK_SIGN")).isZero();
            shop.clickBuy(buyer, buyer.getUsername());
            Ledger.waitForPersonalBalance(buyer.getUuid(), buyerBefore.subtract(new BigDecimal("25")), TIMEOUT);
            Ledger.waitForPersonalBalance(owner.getUuid(), ownerBefore.add(new BigDecimal("25")), TIMEOUT);
            assertThat(Inv.playerCount(buyer.getUsername(), "DIRT")).isEqualTo(4);
            assertThat(Inv.chestCount(chest, "DIRT")).isEqualTo(16);
            Market.Sale sale = Market.waitForSaleForCustomer(buyer.getUuid(), TIMEOUT);
            assertThat(sale.quantity()).isEqualTo(4);
            assertThat(sale.totalPrice()).isEqualByComparingTo("25");
        }
    }
}
