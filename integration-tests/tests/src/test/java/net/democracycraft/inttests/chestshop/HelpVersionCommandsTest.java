package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class HelpVersionCommandsTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"chestshop", "cs"})
    void helpReflectsPermissionsAndPaginatesWithoutLosingRoutes(String root) {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Help"))) {
            String name = player.getUsername();
            Perms.grant(name, "chestshop.toggle");
            Perms.grant(name, "griefprevention.spam");
            denyAdmin(name);
            String ordinary = help(player, root);
            assertThat(ordinary).contains("help (page 1/1)", "/chestshop notify", "/chestshop toggle preview")
                    .doesNotContain("/chestshop give", "/chestshop metrics", "/chestshop reload", "/chestshop resync", "/chestshop version");
            Perms.grant(name, "chestshop.admin");
            String first = help(player, root + " help");
            assertThat(first).contains("help (page 1/2)", "/chestshop give", "/chestshop metrics", "/chestshop bypass");
            String last = help(player, root + " help 2");
            assertThat(last).contains("help (page 2/2)", "/chestshop reload", "/chestshop resync", "/chestshop version",
                    "/chestshop toggle hologram", "/chestshop toggle preview", "/chestshop toggle visibility");
            assertThat(help(player, root + " help 0")).isEqualTo(first);
            assertThat(help(player, root + " help 999")).isEqualTo(last);
            denyAdmin(name);
            assertThat(help(player, root + " help")).isEqualTo(ordinary);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chestshop", "cs"})
    void versionReportsLoadedPluginVersionAndRequiresAdmin(String root) {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Ver"))) {
            String name = player.getUsername();
            Perms.grant(name, "chestshop.admin");
            var version = java.util.regex.Pattern.compile("version=(\\S+)").matcher(RconClient.send("diag plugin ChestShop"));
            assertThat(version.find()).isTrue();
            player.drainMessages();
            player.chatCommand("/" + root + " version");
            assertThat(player.awaitMessageContaining("version is:", TIMEOUT)).contains("ChestShop's version is: " + version.group(1));
            denyAdmin(name);
            player.drainMessages();
            player.chatCommand("/" + root + " version");
            Barrier.settle();
            assertThat(player.collectedMessages()).noneMatch(m -> m.contains("version is:"));
        }
    }

    private static String help(MockPlayer player, String command) {
        player.drainMessages();
        player.chatCommand("/" + command);
        String reply = player.awaitMessageContaining("help (page", TIMEOUT);
        Barrier.settle();
        return reply;
    }

    private static void denyAdmin(String name) {
        RconClient.send("lp user " + name + " permission set chestshop.admin false");
        Await.until("admin permission denied", () -> Perms.check(name, "chestshop.admin"),
                r -> r.contains("has=false"), TIMEOUT);
    }
}
