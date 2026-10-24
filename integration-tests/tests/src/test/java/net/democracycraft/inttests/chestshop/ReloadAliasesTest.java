package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "IT_SERVER_DIR", matches = ".+")
class ReloadAliasesTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    @ParameterizedTest
    @ValueSource(strings = {"chestshop", "cs", "csdb"})
    void reloadChangesResolvedItemAndRevokedPermissionCannotReload(String root) throws Exception {
        Path aliases = Path.of(System.getenv("IT_SERVER_DIR"), "plugins", "ChestShop", "itemAliases.yml");
        byte[] original = Files.readAllBytes(aliases);
        try (MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("Rld"))) {
            Perms.grant(admin.getUsername(), "chestshop.admin");
            Perms.grant(admin.getUsername(), "chestshop.find");
            Inv.clear(admin.getUsername());
            try {
                Files.writeString(aliases, "Diamond: ItGem\n");
                reload(admin, root);
                give(admin);
                assertThat(Inv.playerCount(admin.getUsername(), "DIAMOND")).isEqualTo(2);
                assertThat(Inv.playerCount(admin.getUsername(), "EMERALD")).isZero();
                Files.writeString(aliases, "Emerald: ItGem\n");
                RconClient.send("lp user " + admin.getUsername() + " permission set chestshop.admin false");
                Await.until("reload permission revoked", () -> Perms.check(admin.getUsername(), "chestshop.admin"),
                        r -> r.contains("has=false"), TIMEOUT);
                admin.chatCommand("/" + root + " reload");
                Barrier.settle();
                Perms.grant(admin.getUsername(), "chestshop.admin");
                give(admin);
                assertThat(Inv.playerCount(admin.getUsername(), "DIAMOND")).isEqualTo(4);
                assertThat(Inv.playerCount(admin.getUsername(), "EMERALD")).isZero();
                reload(admin, root);
                give(admin);
                assertThat(Inv.playerCount(admin.getUsername(), "DIAMOND")).isEqualTo(4);
                assertThat(Inv.playerCount(admin.getUsername(), "EMERALD")).isEqualTo(2);
            } finally {
                Files.write(aliases, original);
                Perms.grant(admin.getUsername(), "chestshop.admin");
                reload(admin, root);
            }
        }
    }

    private static void reload(MockPlayer admin, String root) {
        admin.chatCommand("/" + root + " reload");
        admin.awaitMessageContaining("reloaded", TIMEOUT);
        Barrier.settle();
    }
    private static void give(MockPlayer admin) {
        admin.chatCommand("/cs give ItGem 2");
        admin.awaitMessageContaining("Given", TIMEOUT);
        Barrier.settle();
    }
}
