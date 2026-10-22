package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

/** Checks current-window counters; the 30-minute rollover is a separate acceptance gap. */
class MetricsCommandTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"chestshop", "cs"})
    void metricsCountActualBuyAndSellItemsAndRespectRevokedPermission(String root) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Metr"));
             MockPlayer trader = MockPlayer.connectLocal(Usernames.fresh("Metr"));
             ChestShopFixture buy = ChestShopFixture.create();
             ChestShopFixture sell = ChestShopFixture.create()) {
            String name = owner.getUsername();
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(trader.getUuid(), TIMEOUT);
            Perms.grant(name, "chestshop.admin");
            RconClient.send("diag warmname " + name);
            buy.setupSellShop(name, 3, 37, "dirt");
            sell.setupBuyShop(name, 2, 11, "dirt");
            int[] before = metrics(owner, root);
            buy.clickBuy(trader, trader.getUsername());
            sell.clickSell(trader, trader.getUsername());
            Barrier.settle();
            int[] after = metrics(owner, root);
            assertThat(after).containsExactly(before[0] + 2, before[1] + 1, before[2] + 1,
                    before[3] + 5, before[4] + 3, before[5] + 2);
            assertThat(Inv.playerCount(trader.getUsername(), "DIRT")).isEqualTo(1);
            assertThat(Inv.chestCount(buy.chestPos(), "DIRT")).isEqualTo(33);
            assertThat(Inv.chestCount(sell.chestPos(), "DIRT")).isEqualTo(2);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10026");
            assertThat(Ledger.personalBalance(trader.getUuid()).orElseThrow()).isEqualByComparingTo("9974");
            RconClient.send("lp user " + name + " permission set chestshop.admin false");
            Await.until("metrics permission revoked", () -> Perms.check(name, "chestshop.admin"),
                    r -> r.contains("has=false"), TIMEOUT);
            owner.drainMessages();
            owner.chatCommand("/" + root + " metrics");
            Barrier.settle();
            assertThat(owner.collectedMessages()).noneMatch(m -> m.contains("Average transactions:"));
        }
    }

    private static int[] metrics(MockPlayer player, String root) {
        player.drainMessages();
        player.chatCommand("/" + root + " metrics");
        String message = player.awaitMessageContaining("Average transactions:", TIMEOUT);
        var match = Pattern.compile("Average transactions: (\\d+) \\(buy: (\\d+) sell: (\\d+)\\).*"
                + "Average items traded: (\\d+) \\(bought: (\\d+) sold: (\\d+)\\)", Pattern.DOTALL).matcher(message);
        assertThat(match.find()).as("metric counters in %s", message).isTrue();
        int[] counts = new int[6];
        for (int i = 0; i < counts.length; i++) counts[i] = Integer.parseInt(match.group(i + 1));
        return counts;
    }
}
