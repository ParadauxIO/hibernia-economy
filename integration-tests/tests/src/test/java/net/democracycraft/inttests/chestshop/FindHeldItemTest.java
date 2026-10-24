package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class FindHeldItemTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"find", "csdb find"})
    void emptyHandGivesGuidanceThenSelectedHotbarItemOpensCorrectSearch(String root) {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Held"))) {
            String name = player.getUsername();
            Perms.grant(name, "chestshop.find");
            RconClient.send("clear " + name);
            player.selectHotbarSlot(0);
            Barrier.sync(player);
            player.chatCommand("/" + root);
            assertThat(player.awaitMessageContaining("Hold an item", TIMEOUT)).contains("/find <code>");
            RconClient.send("item replace entity " + name + " hotbar.0 with minecraft:emerald 7");
            RconClient.send("item replace entity " + name + " hotbar.2 with minecraft:diamond 4");
            player.selectHotbarSlot(2);
            Barrier.sync(player);
            var dialog = Dialogs.await(player, () -> player.chatCommand("/" + root), d -> true);
            assertThat(Dialogs.title(dialog)).contains("Find Shops", "Diamond").doesNotContain("Emerald");
            assertThat(Inv.playerCount(name, "DIAMOND")).isEqualTo(4);
            assertThat(Inv.playerCount(name, "EMERALD")).isEqualTo(7);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"find", "csdb find"})
    void unknownCodeReportsTheRejectedItemAndLeavesInventoryIntact(String root) {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Held"))) {
            String name = player.getUsername();
            Perms.grant(name, "chestshop.find");
            RconClient.send("item replace entity " + name + " hotbar.0 with minecraft:emerald 7");
            player.chatCommand("/" + root + " it_nonexistent_material");
            assertThat(player.awaitMessageContaining("Unknown item", TIMEOUT)).contains("it_nonexistent_material");
            Barrier.settle();
            assertThat(Inv.playerCount(name, "EMERALD")).isEqualTo(7);
        }
    }
}
