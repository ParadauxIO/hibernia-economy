package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class HopperMinecartProtectionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Test
    void liveHopperMinecartCannotSiphonShopButCollectsAfterSignRemoval() {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Cart"));
             ChestShopFixture shop = ChestShopFixture.create()) {
            String tag = Usernames.fresh("cart");
            String cart = "@e[type=minecraft:hopper_minecart,tag=" + tag + ",limit=1]";
            var c = shop.chestPos();
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            shop.setupSellShop(owner.getUsername(), 3, 37, "dirt");
            RconClient.send("diag warmname " + owner.getUsername());
            RconClient.send("setblock " + c.x() + " " + (c.y() - 2) + " " + c.z() + " minecraft:stone");
            RconClient.send("setblock " + c.x() + " " + (c.y() - 1) + " " + c.z() + " minecraft:rail[shape=north_south]");
            try {
                RconClient.send("summon minecraft:hopper_minecart " + (c.x() + 0.5) + " " + (c.y() - 0.9) + " " + (c.z() + 0.5)
                        + " {Tags:[\"" + tag + "\"],NoGravity:1b,Invulnerable:1b}");
                long start = gameTime();
                Await.until("twenty active hopper ticks", HopperMinecartProtectionTest::gameTime,
                        time -> time - start >= 20, TIMEOUT);
                assertThat(RconClient.send("data get entity " + cart + " Items")).contains("[]");
                assertThat(Inv.chestCount(c, "DIRT")).isEqualTo(36);
                assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
                // Positive control on this same cart/container: remove only the sign.
                var sign = shop.signPos();
                RconClient.send("setblock " + sign.x() + " " + sign.y() + " " + sign.z() + " minecraft:air");
                Inv.waitForChestCount(c, "DIRT", 0, TIMEOUT);
                assertThat(RconClient.send("data get entity " + cart + " Items[0].id")).contains("minecraft:dirt");
                assertThat(RconClient.send("data get entity " + cart + " Items[0].count").trim()).endsWith(": 36");
                assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            } finally {
                RconClient.send("kill " + cart);
                RconClient.send("execute positioned " + (c.x() + 0.5) + " " + (c.y() - 1) + " " + (c.z() + 0.5)
                        + " run kill @e[type=minecraft:item,distance=..2]");
                RconClient.send("setblock " + c.x() + " " + (c.y() - 1) + " " + c.z() + " minecraft:air");
                RconClient.send("setblock " + c.x() + " " + (c.y() - 2) + " " + c.z() + " minecraft:air");
            }
        }
    }

    private static long gameTime() {
        return Dialogs.gameTime();
    }
}
