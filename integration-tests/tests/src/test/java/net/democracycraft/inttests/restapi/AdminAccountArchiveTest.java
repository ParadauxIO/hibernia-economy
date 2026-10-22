package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class AdminAccountArchiveTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void archiveBlocksBothTransferDirectionsAndUnarchiveRestoresSameAccountAndKeys() throws Exception {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("AaOwn"));
             MockPlayer peer = MockPlayer.connectLocal(Usernames.fresh("AaPe"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(peer.getUuid(), TIMEOUT);
            int account = Ledger.personalAccountId(owner.getUuid()).orElseThrow();
            int other = Ledger.personalAccountId(peer.getUuid()).orElseThrow();
            var ownerKey = ApiTokens.issuePersonal(owner.getUsername(), owner);
            var peerKey = ApiTokens.issuePersonal(peer.getUsername(), peer);
            String service = ApiTokens.mintServiceKey(peer.getUuid()).token();
            long checkpoint = Ledger.transactionCheckpoint();
            String base = "/admin/accounts/" + account;
            var renamed = RestApi.call("PATCH", base + "/display-name", service, "{\"displayName\":\"  Archive Probe  \"}");
            summary(renamed, account, owner.getUuid(), false);
            state(account, owner.getUuid(), false);
            var denied = RestApi.call("POST", base + "/archive", ownerKey.token(), null);
            assertThat(denied.status()).isEqualTo(403);
            assertThat(denied.error()).isEqualTo("FORBIDDEN");
            state(account, owner.getUuid(), false);
            for (int repeat = 0; repeat < 2; repeat++) {
                summary(RestApi.call("POST", base + "/archive", service, null), account, owner.getUuid(), true);
                state(account, owner.getUuid(), true);
            }
            var outgoing = transfer(ownerKey.token(), other, "7.89", "DeniedOutgoing");
            var incoming = transfer(peerKey.token(), account, "7.89", "DeniedIncoming");
            for (var response : java.util.List.of(outgoing, incoming)) {
                assertThat(response.status()).as("%s", response.body()).isEqualTo(404);
                assertThat(response.error()).isEqualTo("ACCOUNT_NOT_FOUND");
            }
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.accountBalanceById(other).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            var unauthorizedRestore = RestApi.call("POST", base + "/unarchive", peerKey.token(), null);
            assertThat(unauthorizedRestore.status()).isEqualTo(403);
            state(account, owner.getUuid(), true);
            for (int repeat = 0; repeat < 2; repeat++) {
                summary(RestApi.call("POST", base + "/unarchive", service, null), account, owner.getUuid(), false);
                state(account, owner.getUuid(), false);
            }
            assertThat(Ledger.personalAccountId(owner.getUuid())).hasValue(account);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            var paid = transfer(ownerKey.token(), other, "23.45", "RestoredOutgoing");
            receipt(paid, account, other, "23.45");
            var returned = transfer(peerKey.token(), account, "11.00", "RestoredIncoming");
            receipt(returned, other, account, "11.00");
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("9987.55");
            assertThat(Ledger.accountBalanceById(other).orElseThrow()).isEqualByComparingTo("10012.45");
            var ownerPostings = Ledger.initiatedPostingsAfter(owner.getUuid(), checkpoint);
            var peerPostings = Ledger.initiatedPostingsAfter(peer.getUuid(), checkpoint);
            assertThat(ownerPostings).hasSize(2);
            assertThat(peerPostings).hasSize(2);
            assertThat(ownerPostings.stream().filter(p -> p.accountId() == account).map(Ledger.Posting::amount).toList()).containsExactly(new BigDecimal("-23.45"));
            assertThat(ownerPostings.stream().filter(p -> p.accountId() == other).map(Ledger.Posting::amount).toList()).containsExactly(new BigDecimal("23.45"));
            assertThat(peerPostings.stream().filter(p -> p.accountId() == account).map(Ledger.Posting::amount).toList()).containsExactly(new BigDecimal("11.00"));
            assertThat(peerPostings.stream().filter(p -> p.accountId() == other).map(Ledger.Posting::amount).toList()).containsExactly(new BigDecimal("-11.00"));
            assertThat(Ledger.initiatedTransactionCountAfter(owner.getUuid(), checkpoint)).isEqualTo(1);
            assertThat(Ledger.initiatedTransactionCountAfter(peer.getUuid(), checkpoint)).isEqualTo(1);
        }
    }
    private static RestApi.Response transfer(String token, int destination, String amount, String memo) {
        return RestApi.postTransfer(token, "{\"toAccountId\":" + destination + ",\"amount\":\"" + amount + "\",\"memo\":\"" + memo + "\"}", null);
    }
    private static void receipt(RestApi.Response response, int source, int destination, String amount) {
        assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
        assertThat(response.json().path("fromAccountId").asInt()).isEqualTo(source);
        assertThat(response.json().path("toAccountId").asInt()).isEqualTo(destination);
        assertThat(response.json().path("amount").asText()).isEqualTo(amount);
    }
    private static void summary(RestApi.Response response, int account, UUID owner, boolean archived) {
        assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
        assertThat(response.json().size()).isEqualTo(5);
        assertThat(response.json().path("accountId").asInt()).isEqualTo(account);
        assertThat(response.json().path("accountType").asText()).isEqualTo("PERSONAL");
        assertThat(response.json().path("displayName").asText()).isEqualTo("Archive Probe");
        assertThat(response.json().path("ownerUuid").asText()).isEqualTo(owner.toString());
        assertThat(response.json().path("archived").asBoolean()).isEqualTo(archived);
    }
    private static void state(int account, UUID owner, boolean archived) throws Exception {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT display_name, BIN_TO_UUID(owner_uuid_bin), is_archived FROM accounts WHERE account_id = ?")) {
            q.setInt(1, account);
            try (var r = q.executeQuery()) {
                assertThat(r.next()).isTrue();
                assertThat(r.getString(1)).isEqualTo("Archive Probe");
                assertThat(UUID.fromString(r.getString(2))).isEqualTo(owner);
                assertThat(r.getBoolean(3)).isEqualTo(archived);
            }
        }
    }
}
