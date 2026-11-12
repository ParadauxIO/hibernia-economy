package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.ChestShopFixture;
import net.democracycraft.inttests.support.Inv;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

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

    @Test
    void aShopCreatedFromATypedShorthandSellsThatBook() {
        String seller = Usernames.fresh("Shs");
        String buyer = Usernames.fresh("Shb");

        try (MockPlayer s = MockPlayer.connectLocal(seller);
             MockPlayer b = MockPlayer.connectLocal(buyer);
             ChestShopFixture shop = ChestShopFixture.create()) {

            Ledger.waitForPersonalBalance(s.getUuid(), STARTING, TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), STARTING, TIMEOUT);

            // Typed in lower case, as a player might; creation rewrites it to the canonical name.
            assertThat(shop.createSellShopRaw(seller, seller, 1, PRICE, "bk sharpness v"))
                    .contains("cancelled=false");
            assertThat(RconClient.send("diag checksign world "
                    + shop.signPos().x() + " " + shop.signPos().y() + " " + shop.signPos().z()))
                    .contains("front line 3 = 'Bk Sharpness V'");

            RconClient.send(String.format("item replace block %d %d %d container.0 with %s",
                    shop.chestPos().x(), shop.chestPos().y(), shop.chestPos().z(), SHARPNESS_BOOK));
            Inv.clear(buyer);
            RconClient.send("diag warmname " + seller);

            shop.clickBuy(b, buyer);

            Ledger.waitForPersonalBalance(b.getUuid(),
                    STARTING.subtract(BigDecimal.valueOf(PRICE)), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(),
                    STARTING.add(BigDecimal.valueOf(PRICE)), TIMEOUT);
            Inv.waitForPlayerCount(buyer, "ENCHANTED_BOOK", 1, TIMEOUT);
            Inv.waitForChestCount(shop.chestPos(), "ENCHANTED_BOOK", 0, TIMEOUT);
        }
    }

    @Test
    void anAnvilMergedBookSharesTheShorthandOnlyWhenRepairCostIsExcluded() {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Shm"))) {
            try {
                assertThat(signNameWhileHolding(player, MERGED_SHARPNESS_BOOK)).contains("#");

                RconClient.send("diag config excludedItemAttributesRaw repair-cost");

                assertThat(signNameWhileHolding(player, MERGED_SHARPNESS_BOOK)).endsWith("Bk Sharpness V");
            } finally {
                RconClient.send("diag config excludedItemAttributesRaw []");
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
