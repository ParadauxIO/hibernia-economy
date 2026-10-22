package net.democracycraft.inttests.lifecycle;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** Dedicated disposable profile only: never controls a manually supplied external server. */
@EnabledIfEnvironmentVariable(named = "IT_RESTART_ENABLED", matches = "true")
class ServerRestartTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void paperRestartPreservesCommittedLedgerWorldAndShopAndAllowsNextTrade() throws Exception {
        String sellerName = Usernames.fresh("Boot"), buyerName = Usernames.fresh("Buy");
        UUID sellerId, buyerId;
        int sellerAccount, buyerAccount, sellerPostings, buyerPostings;
        ChestShopFixture shop = ChestShopFixture.create();
        Market.Shop registry;
        try (MockPlayer seller = MockPlayer.connectLocal(sellerName);
             MockPlayer buyer = MockPlayer.connectLocal(buyerName)) {
            sellerId = seller.getUuid(); buyerId = buyer.getUuid();
            Ledger.waitForPersonalBalance(sellerId, TIMEOUT);
            Ledger.waitForPersonalBalance(buyerId, TIMEOUT);
            RconClient.send("diag warmname " + sellerName);
            Perms.grant(sellerName, "griefprevention.spam");
            Perms.grant(buyerName, "griefprevention.spam");
            shop.setupSellShop(sellerName, 2, 37, "diamond");
            shop.clickBuy(buyer, buyerName);
            Barrier.settle();
            assertThat(Ledger.personalBalance(sellerId).orElseThrow()).isEqualByComparingTo("10037");
            assertThat(Ledger.personalBalance(buyerId).orElseThrow()).isEqualByComparingTo("9963");
            assertThat(Inv.chestCount(shop.chestPos(), "DIAMOND")).isEqualTo(22);
            sellerAccount = Ledger.personalAccountId(sellerId).orElseThrow();
            buyerAccount = Ledger.personalAccountId(buyerId).orElseThrow();
            sellerPostings = Ledger.postingsCountForAccount(sellerAccount);
            buyerPostings = Ledger.postingsCountForAccount(buyerAccount);
            var p = shop.signPos();
            registry = Market.waitForShopAt("world", p.x(), p.y(), p.z(), TIMEOUT);
            RconClient.send("save-all flush");
        }

        long oldPid = Long.parseLong(System.getenv("IT_PAPER_PID"));
        ProcessHandle old = ProcessHandle.of(oldPid).orElseThrow();
        assertThat(old.isAlive()).as("runner-owned Paper is alive").isTrue();
        boolean crash = Boolean.parseBoolean(System.getenv("IT_CRASH_RECOVERY"));
        assertThat(crash ? old.destroyForcibly() : old.destroy())
                .as(crash ? "SIGKILL accepted for disposable Paper" : "SIGTERM accepted for disposable Paper").isTrue();
        old.onExit().get(60, TimeUnit.SECONDS);
        Path server = Path.of(System.getenv("IT_SERVER_DIR"));
        Process restarted = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms1G", "-Xmx2G", "-Dcom.mojang.eula.agree=true", "-jar", System.getenv("IT_PAPER_JAR"), "--nogui")
                .directory(server.toFile()).redirectErrorStream(true)
                .redirectOutput(server.getParent().resolve("server-restart.log").toFile()).start();
        try {
            Await.until("restarted Paper RCON ready", () -> {
                if (!restarted.isAlive()) throw new AssertionError("Restarted Paper exited: " + restarted.exitValue());
                try { return RconClient.send("diag runtime"); } catch (RuntimeException unavailable) { return "starting"; }
            }, response -> response.contains("minecraft=26.3"), Duration.ofSeconds(120));
            for (String plugin : new String[]{"Treasury", "Business", "ChestShop", "TreasuryAPI", "LuckPerms"}) {
                assertThat(RconClient.send("diag plugin " + plugin)).contains("enabled=true");
            }
            try (MockPlayer seller = MockPlayer.connectLocal(sellerName);
                 MockPlayer buyer = MockPlayer.connectLocal(buyerName)) {
                Barrier.settle();
                assertThat(seller.getUuid()).isEqualTo(sellerId);
                assertThat(buyer.getUuid()).isEqualTo(buyerId);
                assertThat(Ledger.personalAccountId(sellerId)).hasValue(sellerAccount);
                assertThat(Ledger.personalAccountId(buyerId)).hasValue(buyerAccount);
                assertThat(Ledger.postingsCountForAccount(sellerAccount)).isEqualTo(sellerPostings);
                assertThat(Ledger.postingsCountForAccount(buyerAccount)).isEqualTo(buyerPostings);
                assertThat(Ledger.personalBalance(sellerId).orElseThrow()).isEqualByComparingTo("10037");
                assertThat(Ledger.personalBalance(buyerId).orElseThrow()).isEqualByComparingTo("9963");
                assertThat(Inv.playerCount(buyerName, "DIAMOND")).isEqualTo(2);
                assertThat(Inv.chestCount(shop.chestPos(), "DIAMOND")).isEqualTo(22);
                var p = shop.signPos();
                assertThat(Market.waitForShopAt("world", p.x(), p.y(), p.z(), TIMEOUT)).isEqualTo(registry);
                shop.clickBuy(buyer, buyerName);
                Barrier.settle();
                assertThat(Ledger.personalBalance(sellerId).orElseThrow()).isEqualByComparingTo("10074");
                assertThat(Ledger.personalBalance(buyerId).orElseThrow()).isEqualByComparingTo("9926");
                assertThat(Inv.playerCount(buyerName, "DIAMOND")).isEqualTo(4);
                assertThat(Inv.chestCount(shop.chestPos(), "DIAMOND")).isEqualTo(20);
                assertThat(Ledger.postingsCountForAccount(sellerAccount)).isEqualTo(sellerPostings + 1);
                assertThat(Ledger.postingsCountForAccount(buyerAccount)).isEqualTo(buyerPostings + 1);
                shop.close();
            }
        } finally {
            restarted.destroy();
            if (!restarted.waitFor(60, TimeUnit.SECONDS)) {
                restarted.destroyForcibly();
                assertThat(restarted.waitFor(10, TimeUnit.SECONDS)).as("restarted Paper terminated").isTrue();
                throw new AssertionError("Restarted Paper required forced shutdown");
            }
        }
    }
}
