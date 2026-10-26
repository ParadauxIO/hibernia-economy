package net.democracycraft.inttests.chestshop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

/** Real legacy JSONL decoding, Bukkit item deserialization and Treasury MarketApi writes. */
@EnabledIfEnvironmentVariable(named = "IT_SERVER_DIR", matches = ".+")
class LegacyShopImportTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final AtomicInteger COORDINATE = new AtomicInteger(200000);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void importsPersonalOwnerAndReplayPreservesOriginalSnapshot() throws Exception {
        try (MockPlayer owner = admin(); Export export = new Export()) {
            int x = COORDINATE.getAndIncrement();
            RconClient.send("diag warmname " + owner.getUsername());
            ObjectNode row = row(x, owner.getUsername());
            export.write(row + "\n");
            run(owner, "1 accepted", "0 rejected", "Finished reading export");
            Market.Shop before = shop(x);
            assertThat(before.shopOwnerUuid()).isEqualTo(owner.getUuid());
            assertThat(before.shopAccountType()).isEqualTo("PERSONAL");
            assertThat(before.adminShop()).isFalse();
            assertThat(before.material()).isEqualTo("DIAMOND");
            assertThat(before.buyPrice()).isEqualByComparingTo("12.34");
            assertThat(before.sellPrice()).isEqualByComparingTo("5.67");
            assertThat(before.batchQty()).isEqualTo(3);
            assertThat(before.currentStock()).isEqualTo(21);
            assertThat(before.visible()).isFalse();
            assertThat(before.hologram()).isFalse();
            row.put("buy_price", 99).put("stock", 99).put("visible", 1).put("hologram", 1);
            export.write(row + "\n");
            run(owner, "1 accepted", "0 rejected", "Finished reading export");
            assertThat(shop(x)).isEqualTo(before);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            delete(x);
        }
    }

    @Test
    void malformedRowsAreRejectedWhileValidAdminRowsStillImport() throws Exception {
        try (MockPlayer admin = admin(); Export export = new Export()) {
            int x = COORDINATE.getAndIncrement();
            ObjectNode invalid = row(x + 10000, "Admin Shop").put("quantity", 0);
            export.write("not-json\n" + invalid + "\n" + row(x, "Admin Shop") + "\n");
            run(admin, "1 accepted", "2 rejected", "Finished reading export");
            Market.Shop actual = shop(x);
            assertThat(actual.adminShop()).isTrue();
            assertThat(actual.shopOwnerUuid()).isNull();
            assertThat(actual.buyPrice()).isEqualByComparingTo("12.34");
            assertThat(Market.shopAt("world", x + 10000, 210, 200000)).isEmpty();
            delete(x);
        }
    }

    @Test
    void missingWorldStopsAtFailedRowAndCorrectedExportResumes() throws Exception {
        try (MockPlayer admin = admin(); Export export = new Export()) {
            int first = COORDINATE.getAndIncrement(), failing = COORDINATE.getAndIncrement(), last = COORDINATE.getAndIncrement();
            ObjectNode bad = row(failing, "Admin Shop").put("world_uuid", UUID.randomUUID().toString());
            export.write(row(first, "Admin Shop") + "\n" + bad + "\n" + row(last, "Admin Shop") + "\n");
            run(admin, "1 accepted", "0 rejected", "Stopped at line 2");
            Market.Shop before = shop(first);
            assertThat(Market.shopAt("world", failing, 210, 200000)).isEmpty();
            assertThat(Market.shopAt("world", last, 210, 200000)).isEmpty();
            export.write(row(first, "Admin Shop").put("buy_price", 999) + "\n"
                    + row(failing, "Admin Shop") + "\n" + row(last, "Admin Shop") + "\n");
            run(admin, "3 accepted", "0 rejected", "Finished reading export");
            assertThat(shop(first)).isEqualTo(before);
            assertThat(shop(failing).buyPrice()).isEqualByComparingTo("12.34");
            assertThat(shop(last).buyPrice()).isEqualByComparingTo("12.34");
            delete(first); delete(failing); delete(last);
        }
    }

    @Test
    void missingFileRecoversAndUnprivilegedImportCannotCreateRows() throws Exception {
        try (MockPlayer admin = admin(); Export export = new Export()) {
            Files.deleteIfExists(export.path);
            run(admin, "0 accepted", "0 rejected", "Stopped at line 0");
            int x = COORDINATE.getAndIncrement();
            export.write(row(x, "Admin Shop") + "\n");
            try (MockPlayer outsider = MockPlayer.connectLocal(Usernames.fresh("NoIm"))) {
                RconClient.send("lp user " + outsider.getUsername() + " permission set chestshop.admin false");
                Await.until("import permission denied", () -> Perms.check(outsider.getUsername(), "chestshop.admin"),
                        r -> r.contains("has=false"), TIMEOUT);
                outsider.chatCommand("/cs importlegacy");
                Barrier.settle();
                assertThat(Market.shopAt("world", x, 210, 200000)).isEmpty();
            }
            run(admin, "1 accepted", "0 rejected", "Finished reading export");
            assertThat(shop(x).adminShop()).isTrue();
            delete(x);
        }
    }

    private static ObjectNode row(int x, String owner) {
        return JSON.createObjectNode().put("world_uuid", RconClient.send("diag worlduuid world").trim())
                .put("pos_x", x).put("pos_y", 210).put("pos_z", 200000).put("owner_name", owner)
                .put("item_bytes", RconClient.send("diag itembytes DIAMOND").trim())
                .put("buy_price", 12.34).put("sell_price", 5.67).put("quantity", 3).put("stock", 21)
                .put("estimated_capacity", 1707).put("visible", 0).put("hologram", 0).put("last_updated", 1700000000);
    }

    private static MockPlayer admin() {
        MockPlayer p = MockPlayer.connectLocal(Usernames.fresh("LImp"));
        Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
        Perms.grant(p.getUsername(), "chestshop.admin");
        return p;
    }

    private static void run(MockPlayer p, String... expected) {
        p.chatCommand("/chestshop importlegacy");
        assertThat(p.awaitMessageContaining("Legacy import:", TIMEOUT)).contains(expected);
        Barrier.settle();
    }

    private static Market.Shop shop(int x) { return Market.waitForShopAt("world", x, 210, 200000, TIMEOUT); }
    private static void delete(int x) throws Exception {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var p = c.prepareStatement("DELETE FROM chestshop_shop WHERE world='world' AND sign_x=? AND sign_y=210 AND sign_z=200000")) {
            p.setInt(1, x); p.executeUpdate();
        }
    }

    private static class Export implements AutoCloseable {
        final Path path = Path.of(System.getenv("IT_SERVER_DIR"), "plugins", "ChestShop", "legacy-shops.jsonl");
        final byte[] previous;
        Export() throws Exception { previous = Files.exists(path) ? Files.readAllBytes(path) : null; }
        void write(String content) throws Exception { Files.writeString(path, content); }
        public void close() throws Exception {
            if (previous == null) Files.deleteIfExists(path); else Files.write(path, previous);
        }
    }
}
