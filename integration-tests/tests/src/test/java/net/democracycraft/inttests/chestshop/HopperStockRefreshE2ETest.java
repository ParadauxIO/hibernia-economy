package net.democracycraft.inttests.chestshop;

import static org.assertj.core.api.Assertions.assertThat;

import io.paradaux.mockclient.BlockPos;
import io.paradaux.mockclient.MockPlayer;
import java.time.Duration;
import net.democracycraft.inttests.support.Await;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Inv;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Market;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real hopper pushes and pulls with counters enabled by the disposable runner's hopper profile. */
@EnabledIfEnvironmentVariable(named = "IT_HOPPER_PROFILE", matches = "true")
class HopperStockRefreshE2ETest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void incomingAndOutgoingHoppersRefreshActualStockAfterTransfersStop(boolean doubleChest) {
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("Hop"));
                var shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            shop.setupSellShop(owner.getUsername(), 1, 37, "dirt"); // twelve dirt
            var c = shop.chestPos();
            var s = shop.signPos();
            String secondHalf = (c.x() + 1) + " " + c.y() + " " + c.z();
            String input = (c.x() + (doubleChest ? 2 : 1)) + " " + c.y() + " " + c.z();
            String output = c.x() + " " + (c.y() - 1) + " " + c.z();
            String sign = s.x() + " " + s.y() + " " + s.z();
            try {
                if (doubleChest) {
                    String firstHalf = c.x() + " " + c.y() + " " + c.z();
                    RconClient.send("setblock " + firstHalf + " minecraft:chest[facing=north,type=left]");
                    RconClient.send("setblock " + secondHalf + " minecraft:chest[facing=north,type=right]");
                    RconClient.send("item replace block " + firstHalf + " container.0 with minecraft:dirt 12");
                }
                RconClient.send("setblock " + input + " minecraft:hopper[facing=west]");
                RconClient.send("item replace block " + input + " container.0 with minecraft:dirt 5");
                Inv.waitForChestCount(c, "DIRT", 17, TIMEOUT);
                Await.until("hopper restock counter", () -> counter(sign),
                        text -> text.contains("Q 1 : C 17"), TIMEOUT);
                // PAR-544: /find stock follows the same transfers, with nobody opening the chest.
                Await.untilEquals("hopper restock /find stock", () -> findStock(s), 17, TIMEOUT);
                // RCON placement bypasses placement protection, exercising permitted block pulls.
                RconClient.send("setblock " + output + " minecraft:hopper[facing=down]");
                Inv.waitForChestCount(c, "DIRT", 0, TIMEOUT);
                Await.until("hopper drain counter", () -> counter(sign),
                        text -> text.contains("Q 1 : C 0"), TIMEOUT);
                Await.untilEquals("hopper drain /find stock", () -> findStock(s), 0, TIMEOUT);
                assertThat(RconClient.send("data get block " + output + " Items[0].count").trim())
                        .endsWith(": 17");
                assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow())
                        .isEqualByComparingTo("10000");
            } finally {
                RconClient.send("setblock " + input + " minecraft:air");
                RconClient.send("setblock " + output + " minecraft:air");
                if (doubleChest) RconClient.send("setblock " + secondHalf + " minecraft:air");
            }
        }
    }

    private static Integer findStock(BlockPos sign) {
        return Market.shopAt("world", sign.x(), sign.y(), sign.z())
                .map(Market.Shop::currentStock)
                .orElse(null);
    }

    private static String counter(String position) {
        return RconClient.send("data get block " + position + " front_text.messages[1]");
    }
}
