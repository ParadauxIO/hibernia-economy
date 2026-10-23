package net.democracycraft.inttests.chestshop;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.DriverManager;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class ResyncCommandsTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @ParameterizedTest
    @ValueSource(strings = {"chestshop", "cs", "csdb"})
    void resyncRefreshesLoadedShopsPreservesUnloadedRowsAndHonoursRevokedPermission(String root) throws Exception {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("Sync"));
             ChestShopFixture live = ChestShopFixture.create();
             ChestShopFixture orphan = ChestShopFixture.create()) {
            String name = owner.getUsername();
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Perms.grant(name, "chestshop.admin");
            Perms.grant(name, "chestshop.find");
            RconClient.send("diag warmname " + name);
            live.setupSellShop(name, 3, 37, "dirt");
            orphan.setupSellShop(name, 2, 19, "dirt");
            run(owner, root);
            var original = row(live);
            assertThat(original.currentStock()).isEqualTo(36);
            assertThat(original.shopOwnerUuid()).isEqualTo(owner.getUuid());
            assertThat(row(orphan).active()).isTrue();
            live.positionClicker(owner, name);
            owner.chatCommand("/cs toggle visibility false");
            owner.awaitMessageContaining("now hidden", TIMEOUT);
            Barrier.settle();
            // This ungenerated location must not be loaded or deactivated by resync.
            int farX = 1_000_000 + live.signPos().x();
            exec("INSERT INTO chestshop_shop (world,sign_x,sign_y,sign_z,is_admin_shop,material,item_key,item_name,item_custom,buy_price,batch_qty,active,visible,hologram) "
                    + "VALUES ('world'," + farX + ",200,1000000,1,'DIAMOND','DIAMOND','Diamond',0,5,1,1,1,1)");
            try {
                var unloaded = Market.shopAt("world", farX, 200, 1_000_000).orElseThrow();
                live.setupSellShop(name, 5, 91, "dirt");
                var pos = orphan.signPos();
                RconClient.send("setblock " + pos.x() + " " + pos.y() + " " + pos.z() + " minecraft:air");
                run(owner, root);
                var refreshed = row(live);
                assertThat(refreshed.shopId()).isEqualTo(original.shopId());
                assertThat(refreshed.shopOwnerUuid()).isEqualTo(owner.getUuid());
                assertThat(refreshed.buyPrice()).isEqualByComparingTo("91");
                assertThat(refreshed.batchQty()).isEqualTo(5);
                assertThat(refreshed.currentStock()).isEqualTo(60);
                assertThat(refreshed.active()).isTrue();
                assertThat(refreshed.visible()).isFalse();
                assertThat(row(orphan).active()).isFalse();
                assertThat(Market.shopAt("world", farX, 200, 1_000_000).orElseThrow()).isEqualTo(unloaded);
                run(owner, root);
                assertThat(row(live)).isEqualTo(refreshed);
                assertThat(row(orphan).active()).isFalse();
                assertThat(Market.shopAt("world", farX, 200, 1_000_000).orElseThrow()).isEqualTo(unloaded);

                RconClient.send("lp user " + name + " permission set chestshop.admin false");
                Await.until("resync permission revoked", () -> Perms.check(name, "chestshop.admin"),
                        r -> r.contains("has=false"), TIMEOUT);
                owner.drainMessages();
                owner.chatCommand("/" + root + " resync 64");
                // Assert the command rejection itself. Changing stock here races with
                // legitimate chunk discovery, which can refresh it without this command.
                owner.awaitMessage(m -> m.toLowerCase(java.util.Locale.ROOT).contains("permission")
                        || m.toLowerCase(java.util.Locale.ROOT).contains("unknown"), TIMEOUT);
                Barrier.settle();
                assertThat(row(live)).isEqualTo(refreshed);
                assertThat(owner.collectedMessages()).noneMatch(m -> m.contains("Resync queued") || m.contains("loaded chunks") || m.contains("Resync complete"));
                Perms.grant(name, "chestshop.admin");
                var chest = live.chestPos();
                RconClient.send("item replace block " + chest.x() + " " + chest.y() + " " + chest.z() + " container.0 with minecraft:dirt 7");
                run(owner, root);
                assertThat(row(live).currentStock()).isEqualTo(7);
                assertThat(row(live).shopId()).isEqualTo(original.shopId());
                assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
                assertThat(Market.saleCountForCustomer(owner.getUuid())).isZero();
                assertThat(Inv.chestCount(live.chestPos(), "DIRT")).isEqualTo(7);
            } finally {
                exec("DELETE FROM chestshop_shop WHERE world='world' AND sign_x=" + farX + " AND sign_y=200 AND sign_z=1000000");
            }
        }
    }

    private static void run(MockPlayer owner, String root) {
        owner.drainMessages();
        owner.chatCommand("/" + root + " resync 64");
        owner.awaitMessageContaining("Resync complete", TIMEOUT);
        Barrier.settle();
    }

    private static Market.Shop row(ChestShopFixture shop) {
        var p = shop.signPos();
        return Market.waitForShopAt("world", p.x(), p.y(), p.z(), TIMEOUT);
    }

    private static void exec(String sql) throws Exception {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var s = c.createStatement()) { s.execute(sql); }
    }
}
