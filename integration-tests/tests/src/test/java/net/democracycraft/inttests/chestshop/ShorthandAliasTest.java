package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.Await;
import net.democracycraft.inttests.support.Perms;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Inv;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Compatibility with aliases configured before sign shorthands were introduced. */
@EnabledIfEnvironmentVariable(named = "IT_SERVER_DIR", matches = ".+")
class ShorthandAliasTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private Path aliases;
    private byte[] original;

    @BeforeEach
    void saveAliases() throws Exception {
        aliases = Path.of(System.getenv("IT_SERVER_DIR"), "plugins", "ChestShop", "itemAliases.yml");
        original = Files.readAllBytes(aliases);
    }

    @AfterEach
    void restoreAliases() throws Exception {
        if (original != null) {
            Files.write(aliases, original);
            reload();
        }
    }

    @Test
    void configuredPotionAliasStillAppearsOnNewSigns() throws Exception {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Sha"));
             ChestShopFixture shop = ChestShopFixture.create()) {
            String name = owner.getUsername();
            RconClient.send("item replace entity " + name
                    + " weapon.mainhand with minecraft:potion[potion_contents={potion:'minecraft:swiftness'}]");
            Barrier.settle();
            owner.drainMessages();
            owner.chatCommand("/iteminfo");
            String full = owner.awaitMessageContaining("Full Name:", TIMEOUT);
            String code = full.substring(full.indexOf("Full Name:") + "Full Name:".length()).strip();
            owner.awaitMessageContaining("Shop Sign:", TIMEOUT);
            assertThat(code).startsWith("Potion#");
            Files.writeString(aliases, code + ": Speed Juice\n");
            reload();

            owner.drainMessages();
            owner.chatCommand("/iteminfo");
            assertThat(owner.awaitMessageContaining("Shop Sign:", TIMEOUT)).endsWith("Speed Juice");
            assertThat(shop.createSellShopRaw(name, name, 1, 250, "Speed Juice"))
                    .contains("cancelled=false");
            assertThat(RconClient.send("diag checksign world " + shop.signPos().x() + " "
                    + shop.signPos().y() + " " + shop.signPos().z()))
                    .contains("front line 3 = 'Speed Juice'");
        }
    }

    @Test
    void existingSignWithCollidingAliasStillTradesItsConfiguredItem() throws Exception {
        Files.writeString(aliases, "Diamond: Bk Sharpness V\n");
        reload();
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Sho"));
             MockPlayer buyer = MockPlayer.connectLocal(Usernames.fresh("Shb"));
             ChestShopFixture shop = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(buyer.getUuid(), STARTING, TIMEOUT);
            // Install a persisted sign directly: upgrading must not reinterpret its item line.
            shop.setupSellShop(owner.getUsername(), 1, 250, "diamond");
            RconClient.send("data modify block " + shop.signPos().x() + " " + shop.signPos().y()
                    + " " + shop.signPos().z() + " front_text.messages[3] set value 'Bk Sharpness V'");
            assertThat(RconClient.send("diag checksign world " + shop.signPos().x() + " "
                    + shop.signPos().y() + " " + shop.signPos().z()))
                    .contains("front line 3 = 'Bk Sharpness V'");
            Inv.clear(buyer.getUsername());
            RconClient.send("diag warmname " + owner.getUsername());
            shop.clickBuy(buyer, buyer.getUsername());
            Ledger.waitForPersonalBalance(buyer.getUuid(), STARTING.subtract(new BigDecimal("250")), TIMEOUT);
            Ledger.waitForPersonalBalance(owner.getUuid(), STARTING.add(new BigDecimal("250")), TIMEOUT);
            assertThat(Inv.playerCount(buyer.getUsername(), "DIAMOND")).isEqualTo(1);
            assertThat(Inv.playerCount(buyer.getUsername(), "ENCHANTED_BOOK")).isZero();
            assertThat(Inv.chestCount(shop.chestPos(), "DIAMOND")).isEqualTo(11);
        }
    }

    @Test
    void configuredAliasCannotBypassStoredItemDenial() throws Exception {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Shp"));
             ChestShopFixture shop = ChestShopFixture.create()) {
            String name = owner.getUsername();
            Ledger.waitForPersonalBalance(owner.getUuid(), STARTING, TIMEOUT);
            RconClient.send("item replace entity " + name
                    + " weapon.mainhand with minecraft:potion[potion_contents={potion:'minecraft:swiftness'}]");
            Barrier.settle();
            owner.drainMessages();
            owner.chatCommand("/iteminfo");
            String full = owner.awaitMessageContaining("Full Name:", TIMEOUT);
            String code = full.substring(full.indexOf("Full Name:") + "Full Name:".length()).strip();
            owner.awaitMessageContaining("Shop Sign:", TIMEOUT);
            assertThat(code).startsWith("Potion#");
            Files.writeString(aliases, code + ": Speed Juice\n");
            reload();
            String node = "chestshop.shop.create.potion#" + code.split("#", 2)[1];
            Perms.grant(name, "chestshop.shop.create.buy.potion");
            RconClient.send("lp user " + name + " permission set " + node + " false");
            Await.until("explicit potion denial applied", () -> Perms.check(name, node),
                    "has=false set=true"::equals, TIMEOUT);
            assertThat(shop.createSellShopRaw(name, name, 1, 250, "Speed Juice"))
                    .contains("cancelled=true");
            Barrier.settle();
            Ledger.waitForPersonalBalance(owner.getUuid(), STARTING, TIMEOUT);
        }
    }

    private static void reload() {
        assertThat(RconClient.send("chestshop reload")).containsIgnoringCase("reloaded");
        Barrier.settle();
    }
}
