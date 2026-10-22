package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationCommandTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    @Test
    void notificationsToggleAndPersistAcrossReconnectWithoutSuppressingTrades() {
        String ownerName = Usernames.fresh("Note");
        try (MockPlayer buyer = MockPlayer.connectLocal(Usernames.fresh("Buy"));
             ChestShopFixture shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(buyer.getUuid(), TIMEOUT);
            try (MockPlayer owner = MockPlayer.connectLocal(ownerName)) {
                Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
                Perms.grant(ownerName, "chestshop.toggle");
                Perms.grant(ownerName, "griefprevention.spam");
                RconClient.send("diag warmname " + ownerName);
                shop.setupSellShop(ownerName, 2, 10, "dirt");
                shop.clickBuy(buyer, buyer.getUsername());
                owner.awaitMessageContaining(buyer.getUsername() + " bought", TIMEOUT);
                owner.chatCommand("/chestshop notify");
                owner.awaitMessageContaining("no longer receive", TIMEOUT);
                Barrier.settle();
                owner.drainMessages();
                shop.clickBuy(buyer, buyer.getUsername());
                Barrier.settle();
                assertThat(owner.collectedMessages()).noneMatch(m -> m.contains(buyer.getUsername() + " bought"));
                assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10020");
            }
            Await.until("owner disconnected", () -> Perms.check(ownerName, "chestshop.toggle"), "offline"::equals, TIMEOUT);
            try (MockPlayer owner = MockPlayer.connectLocal(ownerName)) {
                Barrier.settle();
                owner.drainMessages();
                shop.clickBuy(buyer, buyer.getUsername());
                Barrier.settle();
                assertThat(owner.collectedMessages()).noneMatch(m -> m.contains(buyer.getUsername() + " bought"));
                assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10030");
                owner.chatCommand("/cs notify");
                owner.awaitMessageContaining("now receive", TIMEOUT);
                shop.clickBuy(buyer, buyer.getUsername());
                owner.awaitMessageContaining(buyer.getUsername() + " bought", TIMEOUT);
                Barrier.settle();
                assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10040");
                assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow()).isEqualByComparingTo("9960");
                assertThat(Inv.playerCount(buyer.getUsername(), "DIRT")).isEqualTo(8);
                assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isEqualTo(16);
            }
        }
    }
}
