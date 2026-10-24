package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class BalanceAccessTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void aliasesReadFundedOfflineAccountsAndRespectLiveContextualPermissions() throws Exception {
        try (var viewer = MockPlayer.connectLocal(Usernames.fresh("BView"));
             var target = MockPlayer.connectLocal(Usernames.fresh("BTarg"))) {
            Ledger.waitForPersonalBalance(viewer.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(target.getUuid(), TIMEOUT);
            Perms.grant(viewer.getUsername(), "griefprevention.spam");
            viewer.chatCommand("/paya player " + target.getUsername() + " 23.45 balance-fixture");
            viewer.awaitMessageContaining("Paid", TIMEOUT);
            Barrier.settle();
            int viewerAccount = Ledger.personalAccountId(viewer.getUuid()).orElseThrow();
            int targetAccount = Ledger.personalAccountId(target.getUuid()).orElseThrow();
            int viewerPostings = Ledger.postingsCountForAccount(viewerAccount);
            int targetPostings = Ledger.postingsCountForAccount(targetAccount);
            long checkpoint = Ledger.transactionCheckpoint();
            target.close();
            Await.until("balance target offline", () -> Perms.check(target.getUsername(), "treasury.balance"),
                    "offline"::equals, TIMEOUT);
            Perms.grant(viewer.getUsername(), "treasury.balance");
            Perms.grant(viewer.getUsername(), "treasury.balance.others");
            for (String alias : List.of("balance", "bal", "money")) {
                assertThat(read(viewer, "/" + alias)).isEqualTo("TREASURY » Your balance: $9,976.55");
                assertThat(read(viewer, "/" + alias + " " + target.getUsername()))
                        .isEqualTo("TREASURY » " + target.getUsername() + "'s balance: $10,023.45");
            }
            // The world-specific deny overrides the global grant without changing sessions.
            RconClient.send("lp user " + viewer.getUsername() + " permission set treasury.balance.others false world=world");
            Await.until("context denies other balances", () -> Perms.check(viewer.getUsername(), "treasury.balance.others"),
                    value -> value.contains("has=false"), TIMEOUT);
            for (String alias : List.of("balance", "bal", "money")) {
                denied(viewer, "/" + alias + " " + target.getUsername());
                assertThat(read(viewer, "/" + alias)).isEqualTo("TREASURY » Your balance: $9,976.55");
            }
            RconClient.send("lp user " + viewer.getUsername() + " permission unset treasury.balance.others world=world");
            Perms.awaitHas(viewer.getUsername(), "treasury.balance.others");
            for (String alias : List.of("balance", "bal", "money"))
                assertThat(read(viewer, "/" + alias + " " + target.getUsername())).isEqualTo("TREASURY » " + target.getUsername() + "'s balance: $10,023.45");
            RconClient.send("lp user " + viewer.getUsername() + " permission set treasury.balance false");
            Await.until("self balance revoked", () -> Perms.check(viewer.getUsername(), "treasury.balance"),
                    value -> value.contains("has=false"), TIMEOUT);
            for (String alias : List.of("balance", "bal", "money")) denied(viewer, "/" + alias);
            assertThat(Ledger.personalBalance(viewer.getUuid()).orElseThrow()).isEqualByComparingTo("9976.55");
            assertThat(Ledger.personalBalance(target.getUuid()).orElseThrow()).isEqualByComparingTo("10023.45");
            assertThat(Ledger.postingsCountForAccount(viewerAccount)).isEqualTo(viewerPostings);
            assertThat(Ledger.postingsCountForAccount(targetAccount)).isEqualTo(targetPostings);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }

    private static String read(MockPlayer player, String command) throws InterruptedException {
        // Pace this long alias journey within vanilla's command-spam budget.
        Thread.sleep(1100);
        player.drainMessages(); player.chatCommand(command);
        String response = player.awaitMessageContaining("balance", TIMEOUT);
        Barrier.settle(); return response;
    }
    private static void denied(MockPlayer player, String command) throws InterruptedException {
        Thread.sleep(1100);
        player.drainMessages(); player.chatCommand(command);
        String response = player.awaitMessage(m -> m.toLowerCase(java.util.Locale.ROOT).contains("permission")
                || m.toLowerCase(java.util.Locale.ROOT).contains("unknown"), TIMEOUT);
        assertThat(response).doesNotContain("9,976.55", "10,023.45");
        Barrier.settle();
        assertThat(player.collectedMessages()).noneMatch(m -> m.contains("9,976.55") || m.contains("10,023.45"));
    }
}
