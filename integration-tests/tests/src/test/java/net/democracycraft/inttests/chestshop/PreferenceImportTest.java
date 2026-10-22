package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** Writes a real export file only inside the runner's disposable server. */
@EnabledIfEnvironmentVariable(named = "IT_SERVER_DIR", matches = ".+")
class PreferenceImportTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void importAndReplayPreserveExistingPlayerChoice() throws Exception {
        try (MockPlayer admin = admin(); ExportFile export = new ExportFile()) {
            UUID imported = UUID.randomUUID();
            Perms.grant(admin.getUsername(), "chestshop.find");
            admin.chatCommand("/cs toggle preview false");
            admin.awaitMessageContaining("holograms are now", TIMEOUT);
            Barrier.settle();
            export.write(row(admin.getUuid(), true) + row(imported, false));
            run(admin, "2 accepted", "Finished.");
            assertThat(preference(admin.getUuid())).isEqualTo(false);
            assertThat(preference(imported)).isEqualTo(false);
            export.write(row(admin.getUuid(), true) + row(imported, true));
            run(admin, "2 accepted", "Finished.");
            assertThat(preference(admin.getUuid())).isEqualTo(false);
            assertThat(preference(imported)).isEqualTo(false);
        }
    }

    @Test
    void malformedLineStopsThenCorrectedExportResumesWithoutOverwriting() throws Exception {
        try (MockPlayer admin = admin(); ExportFile export = new ExportFile()) {
            UUID first = UUID.randomUUID(), last = UUID.randomUUID();
            export.write(row(first, false) + "{\"player_uuid\":\"bad\",\"visible\":true}\n" + row(last, true));
            run(admin, "1 accepted", "Stopped at line 2");
            assertThat(preference(first)).isEqualTo(false);
            assertThat(preference(last)).isNull();
            export.write(row(first, true) + row(last, true));
            run(admin, "2 accepted", "Finished.");
            assertThat(preference(first)).isEqualTo(false);
            assertThat(preference(last)).isEqualTo(true);
        }
    }

    @Test
    void missingFileReportsFailureAndNextAttemptCanRun() throws Exception {
        try (MockPlayer admin = admin(); ExportFile export = new ExportFile()) {
            Files.deleteIfExists(export.path);
            run(admin, "0 accepted", "Stopped at line 0");
            UUID imported = UUID.randomUUID();
            export.write(row(imported, true));
            run(admin, "1 accepted", "Finished.");
            assertThat(preference(imported)).isEqualTo(true);
        }
    }

    @Test
    void unprivilegedPlayerCannotImportExportFile() throws Exception {
        try (MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Imp")); ExportFile export = new ExportFile()) {
            UUID imported = UUID.randomUUID();
            export.write(row(imported, false));
            RconClient.send("lp user " + player.getUsername() + " permission set chestshop.admin false");
            Await.until("denied import permission", () -> Perms.check(player.getUsername(), "chestshop.admin"),
                    r -> r.contains("has=false"), TIMEOUT);
            player.chatCommand("/chestshop importpreferences");
            Barrier.settle();
            assertThat(preference(imported)).isNull();
        }
    }

    private static MockPlayer admin() {
        MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Imp"));
        Perms.grant(player.getUsername(), "chestshop.admin");
        return player;
    }

    private static void run(MockPlayer player, String count, String outcome) {
        player.chatCommand("/chestshop importpreferences");
        assertThat(player.awaitMessageContaining("Preference import:", TIMEOUT)).contains(count, outcome);
        Barrier.settle();
    }

    private static String row(UUID id, boolean visible) {
        return "{\"player_uuid\":\"" + id + "\",\"visible\":" + visible + "}\n";
    }

    private static Boolean preference(UUID id) throws Exception {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var p = c.prepareStatement("SELECT visible FROM chestshop_preview_preference WHERE player_uuid_bin=UNHEX(REPLACE(?,'-',''))")) {
            p.setString(1, id.toString());
            try (var r = p.executeQuery()) { return r.next() ? r.getBoolean(1) : null; }
        }
    }

    private static class ExportFile implements AutoCloseable {
        final Path path = Path.of(System.getenv("IT_SERVER_DIR"), "plugins", "ChestShop", "legacy-preferences.jsonl");
        final byte[] previous;
        ExportFile() throws Exception {
            previous = Files.exists(path) ? Files.readAllBytes(path) : null;
            Files.createDirectories(path.getParent());
        }
        void write(String content) throws Exception { Files.writeString(path, content); }
        public void close() throws Exception {
            if (previous == null) Files.deleteIfExists(path); else Files.write(path, previous);
        }
    }
}
