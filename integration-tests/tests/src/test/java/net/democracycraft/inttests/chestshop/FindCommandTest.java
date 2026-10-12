package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.DevServer;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.RconClient;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live runtime proof of the in-ChestShop {@code /find} feature (PAR-166), driven
 * over the wire against the registry the feature reads through Treasury's
 * {@code ShopQueryApi}. Avoids the personal-account trade path; it exercises the
 * command surface + the registry read/write directly:
 *
 * <ul>
 *   <li>{@code /chestshop resync} (was {@code /find resync} before PAR-322) — scans the loaded world, reconciling the registry:
 *       an orphan row (no live sign) is deactivated via MarketApi.</li>
 *   <li>{@code /chestshop toggle preview} (was {@code /find toggle preview}) — persists the per-player hologram preference
 *       through MarketApi into {@code chestshop_preview_preference}.</li>
 * </ul>
 */
class FindCommandTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String WORLD = "world";

    @Test
    void resyncDeactivatesAnOrphanRegistryRow() throws Exception {
        // A registry row with no matching sign in the world, in the (loaded) spawn chunk.
        int x = 1, y = 100, z = 1;
        deleteShop(x, y, z);
        insertOrphanAdminShop(x, y, z);
        assertThat(shopActive(x, y, z)).isEqualTo(1);

        String user = Usernames.fresh("Fnd");
        try (MockPlayer p = MockPlayer.connectLocal(user)) {
            Ops.run(user, () -> {
                RconClient.send("lp user " + user + " permission set chestshop.admin true");
                RconClient.send("lp user " + user + " permission set chestshop.find true");
                p.drainMessages();
                // Registry maintenance moved off /find onto the /chestshop root (PAR-322).
                p.chatCommand("/chestshop resync 5");
                p.awaitMessageContaining("Resync complete", TIMEOUT);
            });
        }

        // Reconciliation deactivated the orphan (its chunk was scanned, no sign found).
        assertThat(shopActive(x, y, z)).isEqualTo(0);
    }

    @Test
    void findCommandOpensTheSearchDialog() throws Exception {
        String user = Usernames.fresh("Dlg");
        try (MockPlayer p = MockPlayer.connectLocal(user)) {
            Ops.run(user, () -> {
                RconClient.send("lp user " + user + " permission set chestshop.find true");
                p.drainMessages();
                // /find <itemCode> resolves the item and opens the Usher dialog — proving the
                // whole open path: command → FindState → DialogManager → render → wire packet.
                p.chatCommand("/find diamond");
                var dialog = p.awaitClientbound(
                        org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundShowDialogGamePacket.class,
                        pkt -> true, TIMEOUT);
                assertThat(dialog).isNotNull();
            });
        }
    }

    @Test
    void togglePreviewPersistsPerPlayerPreference() throws Exception {
        String user = Usernames.fresh("Prv");
        try (MockPlayer p = MockPlayer.connectLocal(user)) {
            UUID uuid = p.getUuid();
            deletePreference(uuid);

            Ops.run(user, () -> {
                RconClient.send("lp user " + user + " permission set chestshop.find true");
                p.drainMessages();
                // Per-player hologram toggle moved off /find onto /chestshop (PAR-322).
                p.chatCommand("/chestshop toggle preview false");
                p.awaitMessageContaining("holograms are now", TIMEOUT);
            });

            assertThat(previewVisible(uuid)).isEqualTo(0);
        }
    }

    // ── JDBC helpers (assert against the live registry) ─────────────────────────

    private static void insertOrphanAdminShop(int x, int y, int z) throws Exception {
        exec("INSERT INTO chestshop_shop "
                + "(world, sign_x, sign_y, sign_z, is_admin_shop, material, item_key, item_name, "
                + " item_custom, buy_price, batch_qty, active, visible, hologram) VALUES "
                + "('" + WORLD + "', " + x + ", " + y + ", " + z + ", 1, 'DIAMOND', 'DIAMOND', 'Diamond', "
                + " 0, 5.00, 1, 1, 1, 1)");
    }

    private static void deleteShop(int x, int y, int z) throws Exception {
        exec("DELETE FROM chestshop_shop WHERE world='" + WORLD + "' AND sign_x=" + x
                + " AND sign_y=" + y + " AND sign_z=" + z);
    }

    private static Integer shopActive(int x, int y, int z) throws Exception {
        return queryInt("SELECT active FROM chestshop_shop WHERE world='" + WORLD + "' AND sign_x=" + x
                + " AND sign_y=" + y + " AND sign_z=" + z);
    }

    private static void deletePreference(UUID uuid) throws Exception {
        exec("DELETE FROM chestshop_preview_preference WHERE player_uuid_bin = UNHEX(REPLACE('" + uuid + "','-',''))");
    }

    private static Integer previewVisible(UUID uuid) throws Exception {
        return queryInt("SELECT visible FROM chestshop_preview_preference "
                + "WHERE player_uuid_bin = UNHEX(REPLACE('" + uuid + "','-',''))");
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static Integer queryInt(String sql) throws Exception {
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            return r.next() ? r.getInt(1) : null;
        }
    }

    private static Connection conn() throws Exception {
        return DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
    }
}
