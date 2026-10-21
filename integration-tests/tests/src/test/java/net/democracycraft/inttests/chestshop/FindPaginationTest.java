package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.cloudburstmc.nbt.NbtMap;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class FindPaginationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void savedSortAndStockFilterControlPagedResultsAndBackNavigation() {
        List<ChestShopFixture> shops = new ArrayList<>();
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Page"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Perms.grant(owner.getUsername(), "chestshop.find");
            RconClient.send("diag warmname " + owner.getUsername());
            // Pagination exercises result ordering/filtering, not permission gates.
            // Fixture setup plus dialog reopening can exceed vanilla's chat budget
            // on a fast full-suite server; keep this fixture operator-enabled.
            RconClient.send("op " + owner.getUsername());
            try {
                for (int i = 0; i < 9; i++) {
                    ChestShopFixture shop = ChestShopFixture.create();
                    shops.add(shop);
                    shop.setupSellShop(owner.getUsername(), 1, 10 + i, "amethyst_shard");
                    if (i == 8) {
                        var p = shop.chestPos();
                        RconClient.send("item replace block " + p.x() + " " + p.y() + " " + p.z() + " container.0 with minecraft:air");
                    }
                    visibility(owner, shop, true);
                }
                NbtMap main = Dialogs.await(owner, () -> owner.chatCommand("/find amethyst_shard"), d -> true);
                NbtMap sorting = Dialogs.await(owner,
                        () -> Dialogs.click(owner, main, "Sorting…", Map.of()), d -> Dialogs.title(d).equals("Sorting"));
                NbtMap sortedMain = Dialogs.await(owner,
                        () -> Dialogs.click(owner, sorting, "Save", Map.of("dir_BUY_PRICE", "desc", "pri_BUY_PRICE", 80f)),
                        d -> Dialogs.title(d).contains("Find Shops"));
                NbtMap first = search(owner, sortedMain);
                assertThat(Dialogs.title(first)).contains("9 found (page 1/2)");
                assertPrices(first, 18, 17, 16, 15, 14, 13, 12);
                NbtMap second = Dialogs.await(owner,
                        () -> Dialogs.click(owner, first, "Next page ►", Map.of()), d -> Dialogs.title(d).contains("page 2/2"));
                assertPrices(second, 11, 10);
                assertThat(Dialogs.buttons(second).stream().map(b -> Dialogs.text(b.get("label"))))
                        .doesNotContain("Next page ►");
                NbtMap back = Dialogs.await(owner,
                        () -> Dialogs.click(owner, second, "◄ Previous page", Map.of()), d -> Dialogs.title(d).contains("page 1/2"));
                assertPrices(back, 18, 17, 16, 15, 14, 13, 12);

                NbtMap reopened = Dialogs.await(owner, () -> owner.chatCommand("/find amethyst_shard"), d -> true);
                NbtMap filters = Dialogs.await(owner,
                        () -> Dialogs.click(owner, reopened, "Filters…", Map.of()), d -> Dialogs.title(d).equals("Filters"));
                NbtMap filteredMain = Dialogs.await(owner,
                        () -> Dialogs.click(owner, filters, "Save", Map.of("type_buy", "true", "type_sell", "false",
                                "type_both", "false", "show_empty", "false", "show_full", "true")),
                        d -> Dialogs.title(d).contains("Find Shops"));
                NbtMap filtered = search(owner, filteredMain);
                assertThat(Dialogs.title(filtered)).contains("8 found (page 1/2)");
                assertPrices(filtered, 10, 11, 12, 13, 14, 15, 16);
                NbtMap filteredLast = Dialogs.await(owner,
                        () -> Dialogs.click(owner, filtered, "Next page ►", Map.of()), d -> Dialogs.title(d).contains("page 2/2"));
                assertPrices(filteredLast, 17);
                assertThat(Inv.playerCount(owner.getUsername(), "AMETHYST_SHARD")).isZero();
                assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            } finally {
                try {
                    for (ChestShopFixture shop : shops) {
                        // Fixture teardown only: synthetic break deactivates the registry,
                        // without sending nine more player commands into vanilla spam limits.
                        var p = shop.signPos();
                        try {
                            RconClient.send("diag break " + owner.getUsername() + " world " + p.x() + " " + p.y() + " " + p.z());
                        } finally { shop.close(); }
                    }
                } finally {
                    RconClient.send("deop " + owner.getUsername());
                }
            }
        }
    }

    private static NbtMap search(MockPlayer player, NbtMap main) {
        return Dialogs.await(player, () -> Dialogs.click(player, main, "Search", Map.of("fuzzy", "false")),
                d -> Dialogs.title(d).contains("found (page"));
    }

    private static void assertPrices(NbtMap dialog, int... prices) {
        var labels = Dialogs.buttons(dialog).stream().map(b -> Dialogs.text(b.get("label")))
                .filter(label -> label.contains("Buy $")).toList();
        assertThat(labels).hasSize(prices.length);
        for (int i = 0; i < prices.length; i++) assertThat(labels.get(i)).contains("Buy $" + prices[i] + ".00");
    }

    private static void visibility(MockPlayer owner, ChestShopFixture shop, boolean visible) {
        shop.positionClicker(owner, owner.getUsername());
        owner.chatCommand("/cs toggle visibility " + visible);
        owner.awaitMessageContaining("now", TIMEOUT);
        Barrier.settle();
    }
}
