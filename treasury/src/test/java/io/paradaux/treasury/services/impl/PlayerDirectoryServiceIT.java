package io.paradaux.treasury.services.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paradaux.treasury.services.PlayerDirectoryService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayerDirectoryServiceIT extends IntegrationTestBase {

    private PlayerDirectoryService directory;

    @BeforeEach
    void setUp() {
        directory = injector.getInstance(PlayerDirectoryService.class);
    }

    @Test
    void publicApiUsesTransactionalDirectoryOnCallerAndWorkerThreads() throws Exception {
        UUID uuid = UUID.randomUUID();
        directory.recordLogin(uuid, ".Known", 1000L);
        var api = injector.getInstance(io.paradaux.treasury.api.impl.TreasuryApiImpl.class);
        assertThat(api.resolveKnownPlayerUuid(".KNOWN")).isEqualTo(uuid);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var resolved = executor.submit(() -> api.resolveKnownPlayerUuid(" .known "));
            assertThat(resolved.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(uuid);
            var unknown = executor.submit(() -> api.resolveKnownPlayerUuid("Unseen"));
            assertThat(unknown.get(10, java.util.concurrent.TimeUnit.SECONDS)).isNull();
        }
    }

    @Test
    void recordLogin_thenResolveByNameAndUuid() {
        UUID uuid = UUID.randomUUID();
        directory.recordLogin(uuid, "Dodrio3", 1000L);

        assertThat(directory.resolveUuidByName("Dodrio3")).contains(uuid);
        assertThat(directory.resolveNameByUuid(uuid)).contains("Dodrio3");
    }

    @Test
    void resolveByName_isCaseInsensitiveAndTrimmed() {
        UUID uuid = UUID.randomUUID();
        directory.recordLogin(uuid, "DearEv", 1000L);

        assertThat(directory.resolveUuidByName("dearev")).contains(uuid);
        assertThat(directory.resolveUuidByName("  DEAREV ")).contains(uuid);
    }

    @Test
    void resolveByName_resolvesBedrockNameVerbatim() {
        UUID uuid = UUID.randomUUID();
        directory.recordLogin(uuid, ".Savannah212467", 1000L);

        // The leading Floodgate dot is preserved and the name resolves regardless
        // of usercache state — the whole point for PAR-150 / PAR-149.
        assertThat(directory.resolveUuidByName(".Savannah212467")).contains(uuid);
    }

    @Test
    void resolveByName_unknownOrBlank_isEmpty() {
        assertThat(directory.resolveUuidByName("nobody")).isEmpty();
        assertThat(directory.resolveUuidByName("")).isEmpty();
        assertThat(directory.resolveUuidByName(null)).isEmpty();
    }

    @Test
    void resolveNameByUuid_unknown_isEmpty() {
        assertThat(directory.resolveNameByUuid(UUID.randomUUID())).isEmpty();
    }

    @Test
    void recordLogin_returnsPreviousLoginEpoch() {
        UUID uuid = UUID.randomUUID();
        // First ever login — no previous epoch.
        assertThat(directory.recordLogin(uuid, "Casca", 1000L)).isNull();
        // Re-login returns the epoch the prior call recorded, then advances it.
        assertThat(directory.recordLogin(uuid, "Casca", 2000L)).isEqualTo(1000L);
        assertThat(directory.recordLogin(uuid, "Casca", 3000L)).isEqualTo(2000L);
    }

    @Test
    void recordLogin_upsertUpdatesNameOnReLogin() {
        UUID uuid = UUID.randomUUID();
        directory.recordLogin(uuid, "OldName", 1000L);
        directory.recordLogin(uuid, "NewName", 2000L);

        assertThat(directory.resolveNameByUuid(uuid)).contains("NewName");
        // The old name no longer resolves to this player (same row, name replaced).
        assertThat(directory.resolveUuidByName("OldName")).isEmpty();
    }

    @Test
    void recordLogin_nameTakenByAnotherPlayer_requiresAdminResolution() throws Exception {
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        directory.recordLogin(oldOwner, "Steve", 1000L);
        assertThatThrownBy(() -> directory.recordLogin(newOwner, "steve", 2000L))
                .isInstanceOf(RuntimeException.class);
        assertThat(directory.resolveUuidByName("Steve")).contains(oldOwner);
        assertThat(directory.resolveNameByUuid(newOwner)).isEmpty();
        assertThat(directory.recordLogin(oldOwner, "Steve", 3000L)).isEqualTo(1000L);
    }

    @Test
    void recordLogin_conflictingRename_keepsBothNamesAndEpochs() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        directory.recordLogin(a, "Alpha", 1000L);
        directory.recordLogin(b, "Beta", 2000L);
        assertThatThrownBy(() -> directory.recordLogin(a, "BETA", 3000L)).isInstanceOf(RuntimeException.class);
        assertThat(directory.resolveNameByUuid(a)).contains("Alpha");
        assertThat(directory.resolveNameByUuid(b)).contains("Beta");
        assertThat(directory.recordLogin(a, "Alpha", 4000L)).isEqualTo(1000L);
        assertThat(directory.recordLogin(b, "Beta", 5000L)).isEqualTo(2000L);
    }

    @Test
    void placeholderName_neverResolvesAsAPlayerName() throws Exception {
        UUID uuid = UUID.randomUUID();
        String hex = uuid.toString().replace("-", "").toUpperCase(Locale.ROOT);
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO economy_players(player_uuid_bin,current_name,last_login_epoch) VALUES (UNHEX(?),?,1000)")) {
            ps.setString(1, hex);
            ps.setString(2, hex);
            ps.executeUpdate();
        }
        assertThat(directory.resolveUuidByName(hex)).isEmpty();
        assertThat(directory.resolveNameByUuid(uuid)).isEmpty();
        assertThat(directory.recordLogin(uuid, ".RestoredPlayer", 5000L)).isEqualTo(1000L);
    }
}
