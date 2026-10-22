package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import static org.assertj.core.api.Assertions.assertThat;

class FirmBalanceRankingTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"business", "firm", "company", "db", "democracybusiness"})
    void ranksAggregateBalancesExcludesDisbandedFirmAndHonoursRevokedPermission(String root) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("FTopO"));
             MockPlayer secondOwner = MockPlayer.connectLocal(Usernames.fresh("FTopS"));
             MockPlayer viewer = MockPlayer.connectLocal(Usernames.fresh("FTopV"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(viewer.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(secondOwner.getUuid(), TIMEOUT);
            BigDecimal baseline = highestTotal();
            String first = Usernames.fresh("FTopA");
            String second = Usernames.fresh("FTopB");
            Ops.run(owner.getUsername(), () -> {
                command(owner, "/eco set " + owner.getUsername() + " " + baseline.add(new BigDecimal("1000")));
                seedFirm(owner, root, first, baseline.add(new BigDecimal("20")), new BigDecimal("80"));
            });
            Ops.run(secondOwner.getUsername(), () -> {
                command(secondOwner, "/eco set " + secondOwner.getUsername() + " " + baseline.add(new BigDecimal("1000")));
                seedFirm(secondOwner, root, second, baseline.add(new BigDecimal("70")), new BigDecimal("10"));
            });
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("900");
            Perms.grant(viewer.getUsername(), "business.baltop");
            NumberFormat currency = NumberFormat.getCurrencyInstance(Locale.US);
            String firstRow = "1. " + first + " - " + currency.format(baseline.add(new BigDecimal("100")));
            String secondRow = "2. " + second + " - " + currency.format(baseline.add(new BigDecimal("80")));
            long checkpoint = Ledger.transactionCheckpoint();
            var initial = command(viewer, "/" + root + " baltop");
            assertThat(initial).anyMatch(m -> m.contains("Top Firms") && m.contains("Page 1/"));
            assertThat(rows(initial)).startsWith(firstRow, secondRow);
            for (String page : List.of("1", "0", "-1")) {
                assertThat(rows(command(viewer, "/" + root + " baltop " + page))).isEqualTo(rows(initial));
            }
            var empty = command(viewer, "/" + root + " baltop 9999999");
            assertThat(empty).anyMatch(m -> m.contains("No firms to display."));
            assertThat(rows(empty)).isEmpty();
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            Ops.run(owner.getUsername(), () -> command(owner, "/" + root + " admin disband " + first));
            assertThat(BusinessLedger.firmIsArchived(first)).isTrue();
            long afterDisband = Ledger.transactionCheckpoint();
            var remaining = command(viewer, "/" + root + " baltop");
            assertThat(rows(remaining)).startsWith("1. " + second + " - " + currency.format(baseline.add(new BigDecimal("80"))));
            assertThat(remaining).noneMatch(m -> m.contains(first));
            RconClient.send("lp user " + viewer.getUsername() + " permission set business.baltop false");
            Await.until("firm leaderboard node revoked", () -> Perms.check(viewer.getUsername(), "business.baltop"), r -> r.contains("has=false"), TIMEOUT);
            for (String suffix : List.of("", " 1")) {
                var denied = command(viewer, "/" + root + " baltop" + suffix);
                assertThat(rows(denied)).isEmpty();
                assertThat(denied).noneMatch(m -> m.contains("Top Firms"));
            }
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(afterDisband);
            assertThat(Ledger.personalBalance(viewer.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(secondOwner.getUuid()).orElseThrow()).isEqualByComparingTo("920");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo(baseline.add(new BigDecimal("1000")));
        }
    }

    private static void seedFirm(MockPlayer owner, String root, String name, BigDecimal primaryAmount, BigDecimal reserveAmount) {
        assertThat(command(owner, "/" + root + " create " + name)).anyMatch(m -> m.contains("Successfully created"));
        int id = BusinessLedger.waitForFirm(name, TIMEOUT);
        int primary = BusinessLedger.firmDefaultAccountId(id).orElseThrow();
        command(owner, "/" + root + " account create " + name + " Reserve" + name);
        BusinessLedger.waitForFirmAccountCountAtLeast(id, 2, TIMEOUT);
        int reserve = BusinessLedger.firmAccountIdAt(id, 1);
        command(owner, "/paya account " + primary + " " + primaryAmount);
        command(owner, "/paya account " + reserve + " " + reserveAmount);
        Ledger.waitForAccountBalanceById(primary, primaryAmount, TIMEOUT);
        Ledger.waitForAccountBalanceById(reserve, reserveAmount, TIMEOUT);
    }

    private static BigDecimal highestTotal() {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT COALESCE(MAX(total),0) FROM (SELECT SUM(b.balance) total FROM firm f JOIN firm_accounts a ON a.firm_id=f.firm_id AND a.removed_at IS NULL JOIN account_balances_mat b ON b.account_id=a.account_id WHERE f.is_archived=0 GROUP BY f.firm_id) totals");
             var r = q.executeQuery()) {
            r.next(); return r.getBigDecimal(1).max(BigDecimal.ZERO);
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private static List<String> rows(List<String> messages) {
        return messages.stream().filter(m -> m.matches("\\d+\\. .* - .*" )).toList();
    }

    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages(); player.chatCommand(command); Barrier.settle();
        return player.collectedMessages();
    }
}
