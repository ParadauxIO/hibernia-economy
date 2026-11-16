package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Real shift-clicks must reject a missing container before moving money or items. */
class MissingShopContainerTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final java.util.Map<String, String> original = new java.util.LinkedHashMap<>();

    @BeforeEach
    void saveConfig() {
        for (String field : new String[]{"shiftSellsInStacks", "shiftAllows", "reverseButtons"}) {
            String reply = RconClient.send("diag configget " + field);
            var matcher = java.util.regex.Pattern.compile(" = (\\S+)").matcher(reply);
            assertThat(matcher.find()).as(reply).isTrue();
            original.put(field, matcher.group(1));
        }
    }

    @AfterEach
    void restoreConfig() {
        original.forEach(MissingShopContainerTest::setConfig);
    }

    private static void setConfig(String field, String value) {
        assertThat(RconClient.send("diag config " + field + " " + value)).contains(" = " + value);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shiftBuyWithoutContainerRejectsWithoutSideEffects(boolean reverseButtons) {
        setConfig("shiftSellsInStacks", "true");
        setConfig("shiftAllows", "ALL");
        setConfig("reverseButtons", Boolean.toString(reverseButtons));

        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("MsOwn"));
             MockPlayer buyer = MockPlayer.connectLocal(Usernames.fresh("MsBuy"));
             ChestShopFixture shop = ChestShopFixture.create()) {
            var ownerStart = Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            var buyerStart = Ledger.waitForPersonalBalance(buyer.getUuid(), TIMEOUT);
            Inv.clear(buyer.getUsername());
            shop.setupSellShop(owner.getUsername(), 1, 5, "dirt");
            buyer.setSneaking(true);
            Barrier.sync(buyer);
            // Positive control: shift-buy takes all twelve stocked items, not the sign's single item.
            clickBuy(shop, buyer, reverseButtons);
            Ledger.waitForPersonalBalance(buyer.getUuid(), buyerStart.subtract(new BigDecimal("60")), TIMEOUT);
            Ledger.waitForPersonalBalance(owner.getUuid(), ownerStart.add(new BigDecimal("60")), TIMEOUT);
            assertThat(Inv.playerCount(buyer.getUsername(), "DIRT")).isEqualTo(12);
            assertThat(Inv.chestCount(shop.chestPos(), "DIRT")).isZero();

            int ownerAccount = Ledger.personalAccountId(owner.getUuid()).orElseThrow();
            int buyerAccount = Ledger.personalAccountId(buyer.getUuid()).orElseThrow();
            int ownerPostings = Ledger.postingsCountForAccount(ownerAccount);
            int buyerPostings = Ledger.postingsCountForAccount(buyerAccount);
            long checkpoint = Ledger.transactionCheckpoint();
            var chest = shop.chestPos();
            // Keep the standing sign supported, but remove its inventory.
            RconClient.send("setblock " + chest.x() + " " + chest.y() + " " + chest.z() + " minecraft:stone");
            clickBuy(shop, buyer, reverseButtons);
            buyer.awaitMessageContaining("The shop cannot be used!", TIMEOUT);
            Barrier.settle(buyer, owner);
            assertThat(Ledger.personalBalance(buyer.getUuid()).orElseThrow())
                    .isEqualByComparingTo(buyerStart.subtract(new BigDecimal("60")));
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow())
                    .isEqualByComparingTo(ownerStart.add(new BigDecimal("60")));
            assertThat(Ledger.postingsCountForAccount(ownerAccount)).isEqualTo(ownerPostings);
            assertThat(Ledger.postingsCountForAccount(buyerAccount)).isEqualTo(buyerPostings);
            assertThat(Ledger.initiatedTransactionCountAfter(buyer.getUuid(), checkpoint)).isZero();
            assertThat(Inv.playerCount(buyer.getUsername(), "DIRT")).isEqualTo(12);
        }
    }

    private static void clickBuy(ChestShopFixture shop, MockPlayer player, boolean reverseButtons) {
        if (reverseButtons) shop.clickSell(player, player.getUsername());
        else shop.clickBuy(player, player.getUsername());
    }
}
