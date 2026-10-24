package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GovernmentRoleTransitionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"gov", "government"})
    void weakerGrantsPreserveAuthorizerAndDemotionRemovesOnlyManagement(String root) {
        try (MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("GAdm"));
             MockPlayer actor = MockPlayer.connectLocal(Usernames.fresh("GAuth"));
             MockPlayer target = MockPlayer.connectLocal(Usernames.fresh("GRead"))) {
            for (MockPlayer p : List.of(admin, actor, target)) Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            for (String node : List.of("treasury.gov", "treasury.gov.account.view", "treasury.gov.account.manage", "griefprevention.spam")) {
                Perms.grant(actor.getUsername(), node);
            }
            Perms.grant(target.getUsername(), "treasury.gov");
            Perms.grant(target.getUsername(), "treasury.gov.account.view");
            Ops.run(admin.getUsername(), () -> {
                String name = Usernames.fresh("GRol");
                command(admin, "/" + root + " account create " + name);
                int account = Ledger.waitForGovernmentAccount(name, TIMEOUT);
                command(admin, "/paya government " + name + " 100 RoleSeed");
                Ledger.waitForGovernmentBalance(name, new BigDecimal("100"), TIMEOUT);
                command(admin, "/" + root + " account member add " + name + " " + actor.getUsername());
                command(admin, "/" + root + " account auth add " + name + " " + actor.getUsername());
                assertThat(activeLevel(account, actor.getUuid())).isEqualTo("AUTHORIZER");
                // Lower-tier grants/removal must not silently downgrade the authorizer.
                command(admin, "/" + root + " account viewer add " + name + " " + actor.getUsername());
                command(admin, "/" + root + " account member add " + name + " " + actor.getUsername());
                command(admin, "/" + root + " account viewer remove " + name + " " + actor.getUsername());
                assertThat(activeLevel(account, actor.getUuid())).isEqualTo("AUTHORIZER");
                long checkpoint = Ledger.transactionCheckpoint();
                assertThat(command(actor, "/" + root + " account viewer add " + name + " " + target.getUsername()))
                        .anyMatch(m -> m.contains("Granted") && m.contains(target.getUsername()));
                assertThat(activeLevel(account, target.getUuid())).isEqualTo("VIEWER");
                command(actor, "/" + root + " payout " + name + " " + target.getUsername() + " 7 AuthorizerPayment");
                Ledger.waitForPersonalBalance(target.getUuid(), new BigDecimal("10007"), TIMEOUT);

                command(admin, "/" + root + " account auth remove " + name + " " + actor.getUsername());
                assertThat(activeLevel(account, actor.getUuid())).isEqualTo("MEMBER");
                assertDenied(command(actor, "/" + root + " account viewer remove " + name + " " + target.getUsername()));
                assertThat(activeLevel(account, target.getUuid())).isEqualTo("VIEWER");
                command(actor, "/" + root + " payout " + name + " " + target.getUsername() + " 3 MemberPayment");
                Ledger.waitForPersonalBalance(target.getUuid(), new BigDecimal("10010"), TIMEOUT);
                command(admin, "/" + root + " account viewer remove " + name + " " + actor.getUsername());
                assertThat(activeLevel(account, actor.getUuid())).isEqualTo("MEMBER");
                command(admin, "/" + root + " account member remove " + name + " " + actor.getUsername());
                assertThat(activeLevel(account, actor.getUuid())).isNull();
                assertDenied(command(actor, "/" + root + " payout " + name + " " + target.getUsername() + " 1 ForbiddenPayment"));
                assertDenied(command(actor, "/" + root + " account balance " + name));
                // Removing membership must likewise preserve a pure viewer.
                command(admin, "/" + root + " account member remove " + name + " " + target.getUsername());
                assertThat(activeLevel(account, target.getUuid())).isEqualTo("VIEWER");
                assertThat(command(target, "/" + root + " account balance " + name)).anyMatch(m -> m.contains(name + " balance: $90.00"));
                var postings = Ledger.initiatedPostingsAfter(actor.getUuid(), checkpoint);
                assertThat(postings).hasSize(4);
                int targetAccount = Ledger.personalAccountId(target.getUuid()).orElseThrow();
                assertThat(postings.stream().filter(p -> p.accountId() == account).map(Ledger.Posting::amount).toList())
                        .containsExactly(new BigDecimal("-7.00"), new BigDecimal("-3.00"));
                assertThat(postings.stream().filter(p -> p.accountId() == targetAccount).map(Ledger.Posting::amount).toList())
                        .containsExactly(new BigDecimal("7.00"), new BigDecimal("3.00"));
                assertThat(Ledger.governmentBalance(name).orElseThrow()).isEqualByComparingTo("90");
                assertThat(Ledger.personalBalance(admin.getUuid()).orElseThrow()).isEqualByComparingTo("9900");
                assertThat(Ledger.personalBalance(actor.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
                assertThat(Ledger.personalBalance(target.getUuid()).orElseThrow()).isEqualByComparingTo("10010");
            });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gov", "government"})
    void accountAuthorizerStillNeedsLiveCommandPermissions(String root) {
        try (MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("GAdm"));
             MockPlayer actor = MockPlayer.connectLocal(Usernames.fresh("GPerm"));
             MockPlayer target = MockPlayer.connectLocal(Usernames.fresh("GTarg"))) {
            for (MockPlayer p : List.of(admin, actor, target)) Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            for (String node : List.of("treasury.gov", "treasury.gov.account.view", "treasury.gov.account.manage", "griefprevention.spam")) {
                Perms.grant(actor.getUsername(), node);
            }
            String name = Usernames.fresh("GPerm");
            Ops.run(admin.getUsername(), () -> {
                command(admin, "/" + root + " account create " + name);
                Ledger.waitForGovernmentAccount(name, TIMEOUT);
                command(admin, "/paya government " + name + " 23.45 PermissionSeed");
                command(admin, "/" + root + " account member add " + name + " " + actor.getUsername());
                command(admin, "/" + root + " account auth add " + name + " " + actor.getUsername());
            });
            int account = Ledger.governmentAccountId(name).orElseThrow();
            assertThat(activeLevel(account, actor.getUuid())).isEqualTo("AUTHORIZER");
            long checkpoint = Ledger.transactionCheckpoint();
            assertThat(command(actor, "/" + root + " account balance " + name)).anyMatch(m -> m.contains(name + " balance: $23.45"));
            denyPermission(actor, "treasury.gov.account.view");
            assertDenied(command(actor, "/" + root + " account balance " + name));
            assertDenied(command(actor, "/" + root + " account history " + name));
            Perms.grant(actor.getUsername(), "treasury.gov.account.view");
            assertThat(command(actor, "/" + root + " account balance " + name)).anyMatch(m -> m.contains(name + " balance: $23.45"));
            denyPermission(actor, "treasury.gov.account.manage");
            assertDenied(command(actor, "/" + root + " account viewer add " + name + " " + target.getUsername()));
            assertThat(activeLevel(account, target.getUuid())).isNull();
            Perms.grant(actor.getUsername(), "treasury.gov.account.manage");
            assertThat(command(actor, "/" + root + " account viewer add " + name + " " + target.getUsername()))
                    .anyMatch(m -> m.contains("Granted") && m.contains(target.getUsername()));
            assertThat(activeLevel(account, target.getUuid())).isEqualTo("VIEWER");
            denyPermission(actor, "treasury.gov");
            var history = command(actor, "/" + root + " account history " + name);
            assertThat(history).noneMatch(m -> m.startsWith("#") || m.contains("PermissionSeed") || m.contains("Transactions"));
            command(actor, "/" + root + " payout " + name + " " + target.getUsername() + " 1 ForbiddenRoot");
            assertThat(activeLevel(account, actor.getUuid())).isEqualTo("AUTHORIZER");
            assertThat(Ledger.governmentBalance(name).orElseThrow()).isEqualByComparingTo("23.45");
            assertThat(Ledger.personalBalance(admin.getUuid()).orElseThrow()).isEqualByComparingTo("9976.55");
            assertThat(Ledger.personalBalance(actor.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(target.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }

    private static void denyPermission(MockPlayer player, String node) {
        RconClient.send("lp user " + player.getUsername() + " permission set " + node + " false");
        Await.until("permission denied: " + node, () -> Perms.check(player.getUsername(), node), r -> r.contains("has=false"), TIMEOUT);
    }

    private static String activeLevel(int account, UUID player) {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var statement = c.prepareStatement("SELECT level FROM account_access WHERE account_id = ? AND subject_uuid_bin = UNHEX(REPLACE(?, '-', '')) AND removed_at IS NULL")) {
            statement.setInt(1, account);
            statement.setString(2, player.toString());
            try (var result = statement.executeQuery()) { return result.next() ? result.getString(1) : null; }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages();
        player.chatCommand(command);
        Barrier.settle();
        return player.collectedMessages();
    }

    private static void assertDenied(List<String> messages) {
        assertThat(messages).anyMatch(m -> m.contains("You do not have access to that account."));
        assertThat(messages).noneMatch(m -> m.contains("balance:") || m.contains("Paid"));
    }
}
