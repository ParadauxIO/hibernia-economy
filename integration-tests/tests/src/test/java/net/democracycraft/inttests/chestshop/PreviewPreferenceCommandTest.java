package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class PreviewPreferenceCommandTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"chestshop", "cs", "find", "csdb find"})
    void preferenceIsIsolatedAndCanBeRestoredAfterReconnect(String root) throws Exception {
        String name = Usernames.fresh("Prv");
        UUID uuid;
        try (MockPlayer other = MockPlayer.connectLocal(Usernames.fresh("Prv"))) {
            try (MockPlayer player = MockPlayer.connectLocal(name)) {
                uuid = player.getUuid();
                Perms.grant(name, "chestshop.find");
                Perms.grant(name, "griefprevention.spam");
                set(other, "chestshop", true);
                set(player, root, false);
                assertThat(preference(uuid)).isFalse();
                assertThat(preference(other.getUuid())).isTrue();
            }
            Await.until("preview owner disconnected", () -> Perms.check(name, "chestshop.find"),
                    "offline"::equals, TIMEOUT);
            try (MockPlayer player = MockPlayer.connectLocal(name)) {
                assertThat(player.getUuid()).isEqualTo(uuid);
                Barrier.settle();
                assertThat(preference(uuid)).isFalse();
                set(player, root, true);
                assertThat(preference(uuid)).isTrue();
                set(other, "chestshop", false);
                assertThat(preference(other.getUuid())).isFalse();
                assertThat(preference(uuid)).isTrue();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"find", "csdb find"})
    void finderPermissionRevocationCannotChangeExistingPreference(String root) throws Exception {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Prv"))) {
            String name = player.getUsername();
            Perms.grant(name, "chestshop.find");
            set(player, root, false);
            assertThat(preference(player.getUuid())).isFalse();
            RconClient.send("lp user " + name + " permission set chestshop.find false");
            Await.until("finder permission revoked", () -> Perms.check(name, "chestshop.find"),
                    r -> r.contains("has=false"), TIMEOUT);
            player.drainMessages();
            player.chatCommand("/" + root + " toggle preview true");
            Barrier.settle();
            assertThat(preference(player.getUuid())).isFalse();
            assertThat(player.collectedMessages()).noneMatch(m -> m.contains("holograms are now"));
            // The owner-facing route intentionally remains available without finder permission.
            set(player, "chestshop", true);
            assertThat(preference(player.getUuid())).isTrue();
        }
    }

    private static void set(MockPlayer player, String root, boolean visible) {
        player.drainMessages();
        player.chatCommand("/" + root + " toggle preview " + visible);
        assertThat(player.awaitMessageContaining("holograms are now", TIMEOUT))
                .contains(visible ? "shown" : "hidden");
        Barrier.settle();
    }

    private static Boolean preference(UUID uuid) throws Exception {
        try (var connection = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var query = connection.prepareStatement("SELECT visible FROM chestshop_preview_preference "
                     + "WHERE player_uuid_bin=UNHEX(REPLACE(?,'-',''))")) {
            query.setString(1, uuid.toString());
            try (var result = query.executeQuery()) {
                return result.next() ? result.getBoolean(1) : null;
            }
        }
    }
}
