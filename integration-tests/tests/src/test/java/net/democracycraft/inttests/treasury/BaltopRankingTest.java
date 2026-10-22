package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class BaltopRankingTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void ranksExactBalancesAcrossPagesAndRevokedPermissionStopsEveryRoute() {
        List<MockPlayer> players = new ArrayList<>();
        List<BigDecimal> balances = new ArrayList<>();
        List<Integer> excludedAccounts = new ArrayList<>();
        List<BigDecimal> excludedBalances = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                MockPlayer player = MockPlayer.connectLocal(Usernames.fresh("Rank"));
                players.add(player);
                Ledger.waitForPersonalBalance(player.getUuid(), TIMEOUT);
            }
            // Create distinguishable leaders above existing fixtures, using real admin commands.
            try (MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("RAdm"))) {
                Ledger.waitForPersonalBalance(admin.getUuid(), TIMEOUT);
                BigDecimal highest = Ledger.topPersonalBalances(1).get(0);
                Ops.run(admin.getUsername(), () -> {
                    for (int i = 0; i < players.size(); i++) {
                        BigDecimal amount = highest.add(new BigDecimal("100.37").multiply(BigDecimal.valueOf(i + 1)));
                        balances.add(amount);
                        admin.drainMessages();
                        admin.chatCommand("/eco set " + players.get(i).getUsername() + " " + amount.toPlainString());
                        admin.awaitMessageContaining("Set", TIMEOUT);
                        Ledger.waitForPersonalBalance(players.get(i).getUuid(), amount, TIMEOUT);
                    }
                    String firm = Usernames.fresh("RbFrm");
                    String government = Usernames.fresh("RbGov");
                    admin.drainMessages();
                    admin.chatCommand("/business create " + firm);
                    admin.awaitMessageContaining("Successfully created", TIMEOUT);
                    int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                    admin.drainMessages();
                    admin.chatCommand("/gov account create " + government);
                    admin.awaitMessageContaining("Created", TIMEOUT);
                    Barrier.settle();
                    excludedAccounts.add(BusinessLedger.firmDefaultAccountId(firmId).orElseThrow());
                    excludedAccounts.add(Ledger.governmentAccountId(government).orElseThrow());
                    BigDecimal excludedAmount = highest.add(new BigDecimal("10000"));
                    admin.drainMessages();
                    admin.chatCommand("/eco set " + admin.getUsername() + " "
                            + excludedAmount.multiply(new BigDecimal("2")).add(new BigDecimal("10000")).toPlainString());
                    admin.awaitMessageContaining("Set", TIMEOUT);
                    for (String target : List.of("business " + firm, "government " + government)) {
                        admin.drainMessages();
                        admin.chatCommand("/paya " + target + " " + excludedAmount.toPlainString());
                        admin.awaitMessageContaining("Paid", TIMEOUT);
                        excludedBalances.add(excludedAmount);
                    }
                    Barrier.settle();
                    assertThat(Ledger.personalBalance(admin.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
                    for (int account : excludedAccounts) {
                        assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo(excludedAmount);
                    }
                });
            }
            MockPlayer viewer = players.get(0);
            Perms.grant(viewer.getUsername(), "treasury.baltop");
            long checkpoint = Ledger.transactionCheckpoint();
            List<String> expected = new ArrayList<>();
            NumberFormat currency = NumberFormat.getCurrencyInstance(Locale.US);
            for (int rank = 1; rank <= 12; rank++) {
                int index = 12 - rank;
                expected.add(rank + ". " + players.get(index).getUsername() + " - " + currency.format(balances.get(index)));
            }
            var first = command(viewer, "/baltop");
            assertThat(rows(first)).containsExactlyElementsOf(expected.subList(0, 10));
            assertThat(first).anyMatch(m -> m.contains("Top Balances") && m.contains("Page 1/"));
            assertThat(first).anyMatch(m -> m.contains("Use /baltop 2"));
            assertThat(rows(command(viewer, "/baltop 1"))).containsExactlyElementsOf(expected.subList(0, 10));
            var second = command(viewer, "/baltop 2");
            assertThat(rows(second)).startsWith(expected.get(10), expected.get(11));
            assertThat(rows(second)).noneMatch(m -> expected.subList(0, 10).contains(m));
            assertThat(second).anyMatch(m -> m.contains("Page 2/"));
            // Exercise Paper's stored-name lookup on both pages after confirmed disconnects.
            Perms.grant(players.get(11).getUsername(), "griefprevention.spam");
            for (int index : List.of(11, 1)) {
                MockPlayer offline = players.get(index);
                offline.close();
                Await.until("ranked player disconnects", () -> Perms.check(offline.getUsername(), "treasury.baltop"),
                        "offline"::equals, TIMEOUT);
            }
            assertThat(rows(command(viewer, "/baltop"))).containsExactlyElementsOf(expected.subList(0, 10));
            assertThat(rows(command(viewer, "/baltop 2"))).startsWith(expected.get(10), expected.get(11));
            try (MockPlayer returning = MockPlayer.connectLocal(players.get(11).getUsername())) {
                Barrier.settle();
                assertThat(returning.getUuid()).isEqualTo(players.get(11).getUuid());
                assertThat(rows(command(viewer, "/baltop"))).containsExactlyElementsOf(expected.subList(0, 10));
            }
            assertThat(rows(command(viewer, "/baltop 0"))).containsExactlyElementsOf(expected.subList(0, 10));
            assertThat(rows(command(viewer, "/baltop -1"))).containsExactlyElementsOf(expected.subList(0, 10));
            assertThat(command(viewer, "/baltop help")).anyMatch(m -> m.contains("Balance & Top Help") && m.contains("/baltop"));
            int leaderAccount = Ledger.personalAccountId(players.get(11).getUuid()).orElseThrow();
            String service = ApiTokens.mintServiceKey(viewer.getUuid()).token();
            var archived = RestApi.call("POST", "/admin/accounts/" + leaderAccount + "/archive", service, null);
            assertThat(archived.status()).isEqualTo(200);
            assertThat(archived.json().path("archived").asBoolean()).isTrue();
            List<String> withoutLeader = new ArrayList<>();
            for (int rank = 1; rank <= 10; rank++) {
                int index = 11 - rank;
                withoutLeader.add(rank + ". " + players.get(index).getUsername() + " - " + currency.format(balances.get(index)));
            }
            assertThat(rows(command(viewer, "/baltop"))).containsExactlyElementsOf(withoutLeader);
            assertThat(Ledger.accountBalanceById(leaderAccount).orElseThrow()).isEqualByComparingTo(balances.get(11));
            var restored = RestApi.call("POST", "/admin/accounts/" + leaderAccount + "/unarchive", service, null);
            assertThat(restored.status()).isEqualTo(200);
            assertThat(restored.json().path("archived").asBoolean()).isFalse();
            assertThat(rows(command(viewer, "/baltop"))).containsExactlyElementsOf(expected.subList(0, 10));
            RconClient.send("lp user " + viewer.getUsername() + " permission set treasury.baltop false");
            Await.until("baltop permission revoked", () -> Perms.check(viewer.getUsername(), "treasury.baltop"), r -> r.contains("has=false"), TIMEOUT);
            for (String route : List.of("/baltop", "/baltop 2", "/baltop help")) {
                var denied = command(viewer, route);
                assertThat(rows(denied)).isEmpty();
                assertThat(denied).noneMatch(m -> m.contains("Top Balances") || m.contains("Balance & Top Help"));
            }
            for (int i = 0; i < players.size(); i++) {
                assertThat(Ledger.personalBalance(players.get(i).getUuid()).orElseThrow()).isEqualByComparingTo(balances.get(i));
            }
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            for (int i = 0; i < excludedAccounts.size(); i++) {
                assertThat(Ledger.accountBalanceById(excludedAccounts.get(i)).orElseThrow())
                        .isEqualByComparingTo(excludedBalances.get(i));
            }
        } finally {
            players.forEach(MockPlayer::close);
        }
    }

    private static List<String> command(MockPlayer player, String command) {
        // Let vanilla's command-spam budget recover between this long journey's queries.
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        player.drainMessages();
        player.chatCommand(command);
        Barrier.settle();
        return player.collectedMessages();
    }

    private static List<String> rows(List<String> messages) {
        return messages.stream().filter(m -> m.matches("\\d+\\. .* - .*" )).toList();
    }
}
