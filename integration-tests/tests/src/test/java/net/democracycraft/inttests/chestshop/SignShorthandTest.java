package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.BlockFace;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Await;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Inv;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Perms;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plain potions and single-enchant books get a readable sign name instead of a
 * {@code #code}, on a real server: the name {@code /iteminfo} offers for the sign, a shop
 * created by typing that name, and the goods that shop then sells. Also pins the real
 * item-meta attribute ({@code repair-cost}) an operator excludes to make anvil-merged
 * books share the clean book's name — the unit tests can only prove that key on MockBukkit.
 */
class SignShorthandTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final BigDecimal STARTING = new BigDecimal("10000");
    private static final int PRICE = 250;
    private static final String SHARPNESS_BOOK =
            "minecraft:enchanted_book[stored_enchantments={\"minecraft:sharpness\":5}]";
    private static final String MERGED_SHARPNESS_BOOK =
            "minecraft:enchanted_book[stored_enchantments={\"minecraft:sharpness\":5},repair_cost=1]";

    @Test
    void itemInfoOffersTheShorthandForPlainPotionsAndBooks() {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Shi"))) {
            assertThat(signNameWhileHolding(player, SHARPNESS_BOOK)).endsWith("Bk Sharpness V");
            assertThat(signNameWhileHolding(player,
                    "minecraft:enchanted_book[stored_enchantments={\"minecraft:mending\":1}]"))
                    .endsWith("Bk Mending");
            assertThat(signNameWhileHolding(player,
                    "minecraft:splash_potion[potion_contents={potion:\"minecraft:strong_healing\"}]"))
                    .endsWith("Spl Healing II");
            assertThat(signNameWhileHolding(player,
                    "minecraft:tipped_arrow[potion_contents={potion:\"minecraft:long_swiftness\"}]"))
                    .endsWith("Arr Swiftness Ext");
            // More than one enchantment does not fit a sign line, so the stored code stays.
            assertThat(signNameWhileHolding(player,
                    "minecraft:enchanted_book[stored_enchantments="
                            + "{\"minecraft:sharpness\":5,\"minecraft:unbreaking\":3}]"))
                    .contains("#");
        }
    }

    @ParameterizedTest
    @CsvSource({
            "ENCHANTED_BOOK, minecraft:enchanted_book[stored_enchantments={sharpness:5}], bk sharpness v, Bk Sharpness V, minecraft:enchanted_book[stored_enchantments={mending:1}]",
            "POTION, minecraft:potion[potion_contents={potion:'minecraft:long_swiftness'}], pot swiftness ext, Pot Swiftness Ext, minecraft:potion[potion_contents={potion:'minecraft:swiftness'}]",
            "SPLASH_POTION, minecraft:splash_potion[potion_contents={potion:'minecraft:strong_healing'}], spl healing ii, Spl Healing II, minecraft:splash_potion[potion_contents={potion:'minecraft:healing'}]",
            "LINGERING_POTION, minecraft:lingering_potion[potion_contents={potion:'minecraft:regeneration'}], lin regen, Lin Regen, minecraft:lingering_potion[potion_contents={potion:'minecraft:poison'}]",
            "TIPPED_ARROW, minecraft:tipped_arrow[potion_contents={potion:'minecraft:poison'}], arr poison, Arr Poison, minecraft:tipped_arrow[potion_contents={potion:'minecraft:weakness'}]",
            "ENCHANTED_BOOK, minecraft:enchanted_book[stored_enchantments={sharpness:5}], ?, Bk Sharpness V, minecraft:enchanted_book[stored_enchantments={mending:1}]",
            "ENCHANTED_BOOK, minecraft:enchanted_book[stored_enchantments={sharpness:5}], legacy, Bk Sharpness V, minecraft:enchanted_book[stored_enchantments={mending:1}]"
    })
    void aShopSellsTheExactVariant(String material, String item, String input, String canonical, String decoy) {
        String seller = Usernames.fresh("Shs");
        String buyer = Usernames.fresh("Shb");

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            signNameWhileHolding(s, item);
            if (input.equals("legacy")) {
                input = fullName(s);
                assertThat(input).contains("#");
            }
            assertThat(shop.createSellShopRaw(seller, seller, 1, PRICE, input))
                    .contains("cancelled=false");
            if (input.equals("?")) {
                shop.positionClicker(s, seller);
                s.useBlock(shop.signPos(), BlockFace.SOUTH);
                s.sync(TIMEOUT);
            }
            assertThat(RconClient.send("diag checksign world "
                    + shop.signPos().x() + " " + shop.signPos().y() + " " + shop.signPos().z()))
                    .contains("front line 3 = '" + canonical + "'");

            RconClient.send(String.format("item replace block %d %d %d container.0 with %s",
                    shop.chestPos().x(), shop.chestPos().y(), shop.chestPos().z(), decoy));
            RconClient.send(String.format("item replace block %d %d %d container.1 with %s",
                    shop.chestPos().x(), shop.chestPos().y(), shop.chestPos().z(), item));
            Inv.clear(buyer);
            RconClient.send("diag warmname " + seller);

            shop.clickBuy(b, buyer);

            Ledger.waitForPersonalBalance(b.getUuid(),
                    STARTING.subtract(BigDecimal.valueOf(PRICE)), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(),
                    STARTING.add(BigDecimal.valueOf(PRICE)), TIMEOUT);
            Inv.waitForPlayerCount(buyer, material, 1, TIMEOUT);
            Inv.waitForChestCount(shop.chestPos(), material, 1, TIMEOUT);
            assertThat(RconClient.send("execute if items entity " + buyer + " hotbar.0 " + item
                    + " run data get entity " + buyer + " Inventory[{Slot:0b}].id"))
                    .contains("minecraft:" + material.toLowerCase(java.util.Locale.ROOT));
            assertThat(RconClient.send(String.format(
                    "execute if items block %d %d %d container.0 %s run data get block %d %d %d Items[{Slot:0b}].id",
                    shop.chestPos().x(), shop.chestPos().y(), shop.chestPos().z(), decoy,
                    shop.chestPos().x(), shop.chestPos().y(), shop.chestPos().z())))
                    .contains("minecraft:" + material.toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Test
    void explicitStoredItemDenialStillRejectsShorthandCreation() {
        try (MockPlayer seller = MockPlayer.connectLocal(Usernames.fresh("Shd"));
             ChestShopFixture shop = ChestShopFixture.create()) {
            String name = seller.getUsername();
            Ledger.waitForPersonalBalance(seller.getUuid(), STARTING, TIMEOUT);
            signNameWhileHolding(seller, SHARPNESS_BOOK);
            String code = fullName(seller);
            assertThat(code).contains("#");
            String node = "chestshop.shop.create.enchanted_book#" + code.split("#", 2)[1];
            Perms.grant(name, "chestshop.shop.create.buy.enchanted_book");
            RconClient.send("lp user " + name + " permission set " + node + " false");
            Await.until("explicit item denial applied", () -> Perms.check(name, node),
                    "has=false set=true"::equals, TIMEOUT);
            assertThat(shop.createSellShopRaw(name, name, 1, PRICE, "bk sharpness v"))
                    .contains("cancelled=true");
            Barrier.settle();
            Ledger.waitForPersonalBalance(seller.getUuid(), STARTING, TIMEOUT);
            assertThat(RconClient.send("diag checksign world " + shop.signPos().x() + " "
                    + shop.signPos().y() + " " + shop.signPos().z()))
                    .doesNotContain("front line 3 = 'Bk Sharpness V'");
        }
    }

    private static String fullName(MockPlayer player) {
        String reply = player.awaitMessageContaining("Full Name:", TIMEOUT);
        return reply.substring(reply.indexOf("Full Name:") + "Full Name:".length()).strip();
    }

    @Test
    void anAnvilMergedBookSharesTheShorthandOnlyWhenRepairCostIsExcluded() {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Shm"))) {
            String before = RconClient.send("diag configget excludedItemAttributesRaw");
            assertThat(before).contains(" = [").contains("] cfgHash=");
            String previous = before.substring(before.indexOf(" = [") + 4, before.indexOf("] cfgHash="))
                    .replace(", ", ",");
            try {
                RconClient.send("diag config excludedItemAttributesRaw []");
                assertThat(signNameWhileHolding(player, MERGED_SHARPNESS_BOOK)).contains("#");

                RconClient.send("diag config excludedItemAttributesRaw repair-cost");

                assertThat(signNameWhileHolding(player, MERGED_SHARPNESS_BOOK)).endsWith("Bk Sharpness V");
            } finally {
                RconClient.send("diag config excludedItemAttributesRaw " + (previous.isEmpty() ? "[]" : previous));
            }
        }
    }

    /** The "Shop Sign" line {@code /iteminfo} prints for the given item held in the main hand. */
    private static String signNameWhileHolding(MockPlayer player, String item) {
        RconClient.send("item replace entity " + player.getUsername() + " weapon.mainhand with " + item);
        Barrier.settle();
        player.drainMessages();
        player.chatCommand("/iteminfo");
        return player.awaitMessageContaining("Shop Sign:", TIMEOUT).strip();
    }
}
