package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import static org.assertj.core.api.Assertions.assertThat;

class FirmBalanceLifecycleTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void publicTotalsAndScopedListsTrackArchiveAndDisbandWithoutChangingHistory() {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("FbOwn"));
             MockPlayer reader = MockPlayer.connectLocal(Usernames.fresh("FbRe"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(reader.getUuid(), TIMEOUT);
            String firm = Usernames.fresh("FbFrm");
            setup(owner, "/business create " + firm);
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int primary = BusinessLedger.firmDefaultAccountId(id).orElseThrow();
            setup(owner, "/business account create " + firm + " Reserve");
            int reserve = BusinessLedger.firmAccountIdAt(id, 1);
            setup(owner, "/paya account " + primary + " 23.45 PrimaryTotal");
            setup(owner, "/paya account " + reserve + " 11 ReserveTotal");
            var whole = ApiTokens.issueBusiness(owner.getUsername(), owner, firm);
            var scoped = ApiTokens.issueBusinessAccount(owner, firm, reserve);
            var personal = ApiTokens.issuePersonal(reader.getUsername(), reader);
            long beforeReads = Ledger.transactionCheckpoint();
            var funding = Ledger.initiatedPostingsAfter(owner.getUuid(), 0);
            for (String token : List.of(whole.token(), scoped.token(), personal.token())) {
                balance(token, firm, id, firm, "34.45");
            }
            balance(personal.token(), firm.toUpperCase(Locale.ROOT), id, firm, "34.45");
            assertThat(RestApi.call("GET", "/firms/" + firm + "/balance", null, null).status()).isEqualTo(401);
            var missing = RestApi.getFirmBalanceByName(personal.token(), Usernames.fresh("None"));
            assertThat(missing.status()).isEqualTo(404);
            assertThat(missing.error()).isEqualTo("FIRM_NOT_FOUND");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(beforeReads);
            assertThat(setup(owner, "/business account archive " + firm + " " + reserve))
                    .anyMatch(m -> m.contains("archived account"));
            assertThat(BusinessLedger.activeFirmAccountCount(id)).isEqualTo(1);
            assertThat(BusinessLedger.firmAccountCount(id)).isEqualTo(2);
            balance(personal.token(), firm, id, firm, "23.45");
            var fullList = RestApi.call("GET", "/firms/me/accounts", whole.token(), null);
            assertThat(fullList.status()).isEqualTo(200);
            assertThat(fullList.json().size()).isEqualTo(1);
            assertThat(fullList.json().get(0).path("accountId").asInt()).isEqualTo(primary);
            var scopeList = RestApi.call("GET", "/firms/me/accounts", scoped.token(), null);
            assertThat(scopeList.status()).isEqualTo(200);
            assertThat(scopeList.json().isArray()).isTrue();
            assertThat(scopeList.json().size()).isZero();
            for (String token : List.of(whole.token(), scoped.token())) {
                var rejected = RestApi.call("PATCH", "/firms/me/accounts/" + reserve + "/display-name", token, "{\"displayName\":\"Forbidden\"}");
                assertThat(rejected.status()).isEqualTo(403);
                assertThat(rejected.error()).isEqualTo("FORBIDDEN");
            }
            assertThat(Ledger.accountBalanceById(reserve).orElseThrow()).isEqualByComparingTo("11");
            assertThat(Ledger.initiatedPostingsAfter(owner.getUuid(), 0)).isEqualTo(funding);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(beforeReads);
            assertThat(setup(owner, "/business admin disband " + firm))
                    .anyMatch(m -> m.contains("funds have been returned"));
            long afterDisband = Ledger.transactionCheckpoint();
            balance(personal.token(), firm, id, firm, "0.00");
            var profile = RestApi.call("GET", "/firms/" + firm, personal.token(), null);
            assertThat(profile.status()).isEqualTo(200);
            assertThat(profile.json().path("firmId").asInt()).isEqualTo(id);
            assertThat(profile.json().path("archived").asBoolean()).isTrue();
            assertThat(profile.json().path("defaultAccountId").isNull()).isTrue();
            var empty = RestApi.call("GET", "/firms/me/accounts", whole.token(), null);
            assertThat(empty.status()).isEqualTo(200);
            assertThat(empty.json().isArray()).isTrue();
            assertThat(empty.json().size()).isZero();
            assertThat(BusinessLedger.activeFirmAccountCount(id)).isZero();
            assertThat(BusinessLedger.firmAccountCount(id)).isEqualTo(2);
            assertThat(Ledger.accountBalanceById(primary).orElseThrow()).isEqualByComparingTo("0");
            // Explicitly archived/unlinked funds are retained; disband sweeps current links only.
            assertThat(Ledger.accountBalanceById(reserve).orElseThrow()).isEqualByComparingTo("11");
            assertThat(Ledger.postingsCountForAccount(reserve)).isEqualTo(1);
            assertThat(Ledger.postingsCountForAccount(primary)).isEqualTo(2);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("9989");
            assertThat(Ledger.personalBalance(reader.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(afterDisband);
        }
    }
    private static void balance(String token, String lookup, int id, String name, String amount) {
        var response = RestApi.getFirmBalanceByName(token, lookup);
        assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
        assertThat(response.json().size()).isEqualTo(3);
        assertThat(response.json().path("firmId").asInt()).isEqualTo(id);
        assertThat(response.json().path("displayName").asText()).isEqualTo(name);
        assertThat(response.json().path("totalBalance").asText()).isEqualTo(amount);
    }
    private static List<String> setup(MockPlayer player, String command) {
        var result = new java.util.concurrent.atomic.AtomicReference<List<String>>();
        Ops.run(player.getUsername(), () -> { player.drainMessages(); player.chatCommand(command); Barrier.settle(); result.set(player.collectedMessages()); });
        return result.get();
    }
}
