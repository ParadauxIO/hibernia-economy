package io.paradaux.treasury.services.impl;

import io.paradaux.treasury.services.PlayerDirectoryService;
import io.paradaux.treasury.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerDirectoryServiceIT extends IntegrationTestBase {

    private PlayerDirectoryService directory;

    @BeforeEach
    void setUp() {
        directory = injector.getInstance(PlayerDirectoryService.class);
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
    void recordLogin_nameTakenByAnotherPlayer_reassignsToCurrentOwner() {
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        directory.recordLogin(oldOwner, "Steve", 1000L);
        // newOwner logs in carrying the same name (it was changed/reused). Must not
        // collide on UNIQUE(name_lower); the name follows its current owner.
        directory.recordLogin(newOwner, "Steve", 2000L);

        assertThat(directory.resolveUuidByName("Steve")).contains(newOwner);
        assertThat(directory.resolveNameByUuid(oldOwner)).isEmpty();   // stale claim evicted
        assertThat(directory.resolveNameByUuid(newOwner)).contains("Steve");
    }

    @Test
    void recordLogin_renameOntoAnotherPlayersName_isCollisionSafe() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        directory.recordLogin(a, "Alpha", 1000L);
        directory.recordLogin(b, "Beta", 1000L);
        // 'a' renames onto 'Beta' (which 'b' has since vacated) — existing row + name
        // collision; must reassign without throwing.
        directory.recordLogin(a, "Beta", 3000L);

        assertThat(directory.resolveUuidByName("Beta")).contains(a);
        assertThat(directory.resolveNameByUuid(b)).isEmpty();
        assertThat(directory.resolveNameByUuid(a)).contains("Beta");
    }

    @Test
    void recordLogin_nameTakeover_isCaseInsensitive() {
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        directory.recordLogin(oldOwner, "MixedCase", 1000L);
        directory.recordLogin(newOwner, "mixedcase", 2000L); // same name_lower
        assertThat(directory.resolveUuidByName("MixedCase")).contains(newOwner);
        assertThat(directory.resolveNameByUuid(oldOwner)).isEmpty();
    }

    // ---- Name release keeps the previous holder's row ----

    /** Hex of a UUID as MariaDB's HEX(BINARY(16)) renders it — the placeholder name. */
    private static String hex(UUID uuid) {
        return uuid.toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    private String[] rowOf(UUID uuid) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT current_name, first_seen, last_seen, last_login_epoch "
                             + "FROM economy_players WHERE HEX(player_uuid_bin) = ?")) {
            ps.setString(1, hex(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)};
            }
        }
    }

    @Test
    void recordLogin_nameTakenByAnotherPlayer_keepsThePreviousHoldersLoginEpoch() throws Exception {
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        directory.recordLogin(oldOwner, "Steve", 1000L);
        // Age the row so an unwanted ON UPDATE CURRENT_TIMESTAMP bump would show.
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE economy_players SET first_seen = '2020-01-01 00:00:00', "
                    + "last_seen = '2020-06-01 00:00:00'");
        }

        directory.recordLogin(newOwner, "Steve", 2000L);

        // The stale row survives under the HEX(uuid) placeholder with its timestamps
        // and login epoch untouched — only the name was released.
        String[] row = rowOf(oldOwner);
        assertThat(row).isNotNull();
        assertThat(row[0]).isEqualTo(hex(oldOwner));
        assertThat(row[1]).startsWith("2020-01-01 00:00:00");
        assertThat(row[2]).startsWith("2020-06-01 00:00:00");
        assertThat(row[3]).isEqualTo("1000");

        // So the previous holder's next login is NOT a first login: the balance tax
        // prorates against the preserved epoch instead of skipping the period.
        assertThat(directory.recordLogin(oldOwner, "Steve_Old", 5000L)).isEqualTo(1000L);
        assertThat(directory.resolveNameByUuid(oldOwner)).contains("Steve_Old");
        assertThat(directory.resolveUuidByName("Steve")).contains(newOwner);
    }

    @Test
    void placeholderName_neverResolvesAsAPlayerName() throws Exception {
        UUID oldOwner = UUID.randomUUID();
        UUID newOwner = UUID.randomUUID();
        directory.recordLogin(oldOwner, "Steve", 1000L);
        directory.recordLogin(newOwner, "Steve", 2000L);

        assertThat(rowOf(oldOwner)[0]).isEqualTo(hex(oldOwner));
        assertThat(directory.resolveUuidByName(hex(oldOwner))).isEmpty();
        assertThat(directory.resolveUuidByName(hex(oldOwner).toLowerCase(Locale.ROOT))).isEmpty();
        assertThat(directory.resolveNameByUuid(oldOwner)).isEmpty();
    }
}
