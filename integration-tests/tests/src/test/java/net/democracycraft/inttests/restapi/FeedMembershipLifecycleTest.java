package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class FeedMembershipLifecycleTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void unchangedPersonalTokenTracksGovernmentMembershipButViewerIsNotApiAccess() throws Exception {
        String name = Usernames.fresh("FeedG");
        try (var admin = MockPlayer.connectLocal(Usernames.fresh("FAdm"));
             var member = MockPlayer.connectLocal(Usernames.fresh("FMem"));
             var outsider = MockPlayer.connectLocal(Usernames.fresh("FOut"))) {
            Ledger.waitForPersonalBalance(admin.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(member.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(outsider.getUuid(), TIMEOUT);
            var outsiderKey = ApiTokens.issuePersonal(outsider.getUsername(), outsider);
            var key = ApiTokens.issuePersonal(member.getUsername(), member);
            command(admin, "/gov account create " + name);
            int account = Ledger.waitForGovernmentAccount(name, TIMEOUT);
            long before = Ledger.transactionCheckpoint();
            command(admin, "/paya government " + name + " 23.45 FeedMembership");
            var credits = Ledger.initiatedPostingsAfter(admin.getUuid(), before).stream()
                    .filter(posting -> posting.accountId() == account).toList();
            assertThat(credits).hasSize(1);
            long txn = credits.get(0).transactionId();
            long checkpoint = Ledger.transactionCheckpoint();
            denied(key.token(), account, txn);
            command(admin, "/gov account viewer add " + name + " " + member.getUsername());
            denied(key.token(), account, txn);
            command(admin, "/gov account member add " + name + " " + member.getUsername());
            allowed(key.token(), account, txn, "Payment from " + admin.getUsername() + " to government " + name + ": FeedMembership");
            command(admin, "/gov account auth add " + name + " " + member.getUsername());
            assertThat(activeLevel(account, member.getUuid())).isEqualTo("AUTHORIZER");
            allowed(key.token(), account, txn, "Payment from " + admin.getUsername() + " to government " + name + ": FeedMembership");
            command(admin, "/gov account viewer add " + name + " " + member.getUsername());
            command(admin, "/gov account viewer remove " + name + " " + member.getUsername());
            assertThat(activeLevel(account, member.getUuid())).isEqualTo("AUTHORIZER");
            allowed(key.token(), account, txn, "Payment from " + admin.getUsername() + " to government " + name + ": FeedMembership");
            command(admin, "/gov account auth remove " + name + " " + member.getUsername());
            assertThat(activeLevel(account, member.getUuid())).isEqualTo("MEMBER");
            allowed(key.token(), account, txn, "Payment from " + admin.getUsername() + " to government " + name + ": FeedMembership");
            command(admin, "/gov account member remove " + name + " " + member.getUsername());
            assertThat(activeLevel(account, member.getUuid())).isNull();
            denied(key.token(), account, txn);
            // Regrant without issuing or rotating the token proves request-time membership lookup.
            command(admin, "/gov account member add " + name + " " + member.getUsername());
            allowed(key.token(), account, txn, "Payment from " + admin.getUsername() + " to government " + name + ": FeedMembership");
            command(admin, "/gov account archive " + name);
            assertThat(Ledger.governmentAccountIsArchived(name)).isTrue();
            assertThat(activeLevel(account, member.getUuid())).isEqualTo("MEMBER");
            // Archived accounts retain historical API reads for existing members.
            allowed(key.token(), account, txn, "Payment from " + admin.getUsername() + " to government " + name + ": FeedMembership");
            denied(outsiderKey.token(), account, txn);
            assertThat(ApiTokens.keyById(outsiderKey.keyId()).jwtId()).isEqualTo(outsiderKey.jwtId());
            assertThat(Ledger.personalBalance(outsider.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(ApiTokens.keyById(key.keyId()).jwtId()).isEqualTo(key.jwtId());
            assertThat(ApiTokens.keyById(key.keyId()).revoked()).isFalse();
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("23.45");
            assertThat(Ledger.personalBalance(admin.getUuid()).orElseThrow()).isEqualByComparingTo("9976.55");
            assertThat(Ledger.personalBalance(member.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }

    private static String activeLevel(int account, java.util.UUID player) throws Exception {
        try (var c = java.sql.DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var query = c.prepareStatement("SELECT level FROM account_access WHERE account_id = ? AND subject_uuid_bin = UNHEX(REPLACE(?, '-', '')) AND removed_at IS NULL")) {
            query.setInt(1, account);
            query.setString(2, player.toString());
            try (var rows = query.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }

    private static void command(MockPlayer admin, String command) {
        Ops.run(admin.getUsername(), () -> {
            admin.chatCommand(command);
            Barrier.settle();
        });
    }

    private static void denied(String token, int account, long cursor) {
        for (var response : java.util.List.of(RestApi.getTransactions(token, account, 1, 10),
                RestApi.getTransactionFeed(token, account, 0L, 10),
                RestApi.getTransactionFeed(token, account, cursor, 10))) {
            assertThat(response.status()).as(response.body()).isEqualTo(403);
            assertThat(response.error()).isEqualTo("FORBIDDEN");
        }
    }

    private static void allowed(String token, int account, long txn, String memo) {
        var feed = Await.until("member can read settled government credit", () -> {
            var r = RestApi.getTransactionFeed(token, account, txn - 1, 10);
            assertThat(r.status()).as(r.body()).isEqualTo(200);
            return r;
        }, r -> r.json().path("items").size() == 1, Duration.ofSeconds(20));
        assertThat(feed.json().path("items").get(0).path("txnId").asLong()).isEqualTo(txn);
        assertThat(feed.json().path("items").get(0).path("amount").asText()).isEqualTo("23.45");
        assertThat(feed.json().path("items").get(0).path("memo").asText()).isEqualTo(memo);
        assertThat(feed.json().path("nextCursor").asLong()).isEqualTo(txn);
        var history = RestApi.getTransactions(token, account, 1, 10);
        assertThat(history.status()).as(history.body()).isEqualTo(200);
        assertThat(history.json().path("items").size()).isEqualTo(1);
        assertThat(history.json().path("items").get(0).path("txnId").asLong()).isEqualTo(txn);
    }
}
