package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class FindDialogTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"find", "csdb find"})
    void searchReturnsVisibleShopWithItsPriceAndExcludesHiddenShop(String root) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Find"));
             ChestShopFixture visible = ChestShopFixture.create();
             ChestShopFixture hidden = ChestShopFixture.create()) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Perms.grant(owner.getUsername(), "chestshop.find");
            RconClient.send("diag warmname " + owner.getUsername());
            prepare(owner, visible, 37, true);
            prepare(owner, hidden, 91, false);
            NbtMap main = Dialogs.await(owner, () -> owner.chatCommand("/" + root + " prismarine_shard"), d -> true);
            assertThat(Dialogs.title(main)).contains("Find Shops", "prismarine_shard");
            assertThat(Dialogs.buttons(main).stream().map(b -> Dialogs.text(b.get("label"))))
                    .contains("Search", "Buy — cheapest", "Filters…", "Sorting…");
            NbtMap results = Dialogs.await(owner,
                    () -> Dialogs.click(owner, main, "Buy — cheapest", Map.of("fuzzy", "false")),
                    d -> Dialogs.title(d).contains("found (page"));
            assertThat(Dialogs.title(results)).contains("1 found (page 1/1)");
            String labels = Dialogs.buttons(results).stream().map(b -> Dialogs.text(b.get("label")))
                    .reduce("", (a, b) -> a + "\n" + b);

            assertThat(labels).contains(owner.getUsername(), "Buy $37.00", "36 stock", "qty 3")
                    .doesNotContain("91.00");
            NbtMap inspection = Dialogs.await(owner,
                    () -> Dialogs.click(owner, results, "Inspect item", Map.of()),
                    d -> Dialogs.title(d).contains("Item preview"));
            assertThat(inspection.toString()).contains("minecraft:prismarine_shard", "37.00");
            NbtMap back = Dialogs.await(owner,
                    () -> Dialogs.click(owner, inspection, "Back", Map.of()),
                    d -> Dialogs.title(d).contains("found (page"));
            assertThat(Dialogs.title(back)).isEqualTo(Dialogs.title(results));

            hidden.positionClicker(owner, owner.getUsername());
            owner.chatCommand("/cs toggle visibility true");
            owner.awaitMessageContaining("now shown", TIMEOUT);
            Barrier.settle();
            NbtMap reopened = Dialogs.await(owner, () -> owner.chatCommand("/" + root + " prismarine_shard"), d -> true);
            NbtMap both = Dialogs.await(owner,
                    () -> Dialogs.click(owner, reopened, "Buy — cheapest", Map.of("fuzzy", "false")),
                    d -> Dialogs.title(d).contains("found (page"));
            assertThat(Dialogs.title(both)).contains("2 found (page 1/1)");
            var offers = Dialogs.buttons(both).stream().map(b -> Dialogs.text(b.get("label")))
                    .filter(label -> label.contains("Buy $")).toList();
            assertThat(offers).hasSize(2);
            assertThat(offers.get(0)).contains("37.00");
            assertThat(offers.get(1)).contains("91.00");
            owner.chatCommand("/cs toggle visibility false");
            owner.awaitMessageContaining("now hidden", TIMEOUT);
            Barrier.settle();
            // Hide our visible fixture through the public command before removing its
            // blocks: RCON fill alone does not emit a registry-deactivation event.
            visible.positionClicker(owner, owner.getUsername());
            owner.chatCommand("/cs toggle visibility false");
            owner.awaitMessageContaining("now hidden", TIMEOUT);
            Barrier.settle();
            assertThat(Inv.chestCount(visible.chestPos(), "PRISMARINE_SHARD")).isEqualTo(36);
            assertThat(Inv.playerCount(owner.getUsername(), "PRISMARINE_SHARD")).isZero();
        }
    }

    private static void prepare(MockPlayer owner, ChestShopFixture shop, int price, boolean visible) {
        shop.setupSellShop(owner.getUsername(), 3, price, "prismarine_shard");
        shop.positionClicker(owner, owner.getUsername());
        owner.chatCommand("/cs toggle visibility " + visible);
        owner.awaitMessageContaining("now", TIMEOUT);
        Barrier.settle();
        var pos = shop.signPos();
        assertThat(Market.waitForShopAt("world", pos.x(), pos.y(), pos.z(), TIMEOUT).visible()).isEqualTo(visible);
    }
}
