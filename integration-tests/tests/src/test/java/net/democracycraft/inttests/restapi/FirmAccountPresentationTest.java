package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class FirmAccountPresentationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void accountListsAndDisplayNameUpdatesRespectTokenScopeAndPreserveFunds() throws Exception {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("FaOwn"));
             MockPlayer outsider = MockPlayer.connectLocal(Usernames.fresh("FaOut"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(outsider.getUuid(), TIMEOUT);
            String firm = Usernames.fresh("FaFrm"), foreign = Usernames.fresh("FaFor");
            setup(owner, "/business create " + firm);
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int primary = BusinessLedger.firmDefaultAccountId(id).orElseThrow();
            setup(owner, "/business account create " + firm + " Reserve" + firm);
            int reserve = BusinessLedger.firmAccountIdAt(id, 1);
            setup(owner, "/paya account " + primary + " 23.45 PrimaryList");
            setup(owner, "/paya account " + reserve + " 11 ReserveList");
            setup(outsider, "/business create " + foreign);
            int foreignId = BusinessLedger.waitForFirm(foreign, TIMEOUT);
            int foreignAccount = BusinessLedger.firmDefaultAccountId(foreignId).orElseThrow();
            var whole = ApiTokens.issueBusiness(owner.getUsername(), owner, firm);
            var scoped = ApiTokens.issueBusinessAccount(owner, firm, reserve);
            var foreignKey = ApiTokens.issueBusiness(outsider.getUsername(), outsider, foreign);
            var personal = ApiTokens.issuePersonal(outsider.getUsername(), outsider);
            long checkpoint = Ledger.transactionCheckpoint();
            JsonNode all = accounts(whole.token());
            assertThat(all.size()).isEqualTo(2);
            row(all.get(0), primary, firm + " Corporate Account", "23.45");
            row(all.get(1), reserve, "Reserve" + firm, "11.00");
            JsonNode restricted = accounts(scoped.token());
            assertThat(restricted.size()).isEqualTo(1);
            assertThat(restricted.get(0)).isEqualTo(all.get(1));
            JsonNode other = accounts(foreignKey.token());
            assertThat(other.size()).isEqualTo(1);
            row(other.get(0), foreignAccount, foreign + " Corporate Account", "0.00");
            for (String token : new String[]{personal.token(), null}) {
                assertThat(RestApi.call("GET", "/firms/me/accounts", token, null).status()).isEqualTo(token == null ? 401 : 403);
            }
            for (String token : new String[]{scoped.token(), foreignKey.token(), personal.token(), null}) {
                var rejected = rename(token, primary, "Forbidden");
                assertThat(rejected.status()).isEqualTo(token == null ? 401 : 403);
                assertThat(storedName(primary)).isEqualTo(firm + " Corporate Account");
            }
            var trimmed = rename(scoped.token(), reserve, "  Reserve Updated  ");
            assertThat(trimmed.status()).as("%s", trimmed.body()).isEqualTo(200);
            row(trimmed.json(), reserve, "Reserve Updated", "11.00");
            assertThat(storedName(reserve)).isEqualTo("Reserve Updated");
            String boundary = "R".repeat(255);
            var maximum = rename(whole.token(), reserve, boundary);
            assertThat(maximum.status()).isEqualTo(200);
            row(maximum.json(), reserve, boundary, "11.00");
            for (String body : new String[]{"{}", "{\"displayName\":null}", "{\"displayName\":\"\"}",
                    "{\"displayName\":\"   \"}", JSON.writeValueAsString(Map.of("displayName", "R".repeat(256)))}) {
                var invalid = RestApi.call("PATCH", path(reserve), whole.token(), body);
                assertThat(invalid.status()).as("%s", invalid.body()).isEqualTo(400);
                assertThat(invalid.error()).isEqualTo("INVALID_BODY");
                assertThat(storedName(reserve)).isEqualTo(boundary);
            }
            var primaryRename = rename(whole.token(), primary, "Primary Updated");
            assertThat(primaryRename.status()).isEqualTo(200);
            row(primaryRename.json(), primary, "Primary Updated", "23.45");
            JsonNode finalRows = accounts(whole.token());
            assertThat(finalRows.size()).isEqualTo(2);
            row(finalRows.get(0), primary, "Primary Updated", "23.45");
            row(finalRows.get(1), reserve, boundary, "11.00");
            assertThat(accounts(scoped.token()).get(0)).isEqualTo(finalRows.get(1));
            assertThat(accounts(foreignKey.token())).isEqualTo(other);
            assertThat(BusinessLedger.firmDefaultAccountId(id)).hasValue(primary);
            assertThat(BusinessLedger.activeFirmAccountCount(id)).isEqualTo(2);
            assertThat(Ledger.accountBalanceById(primary).orElseThrow()).isEqualByComparingTo("23.45");
            assertThat(Ledger.accountBalanceById(reserve).orElseThrow()).isEqualByComparingTo("11");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("9965.55");
            assertThat(Ledger.personalBalance(outsider.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
        }
    }

    @Test
    void duplicateDisplayNameCannotMakeFirmAccountsAmbiguous() throws Exception {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("FdOwn"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            String firm = Usernames.fresh("FdFrm");
            setup(owner, "/business create " + firm);
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int primary = BusinessLedger.firmDefaultAccountId(id).orElseThrow();
            setup(owner, "/business account create " + firm + " Reserve" + firm);
            int reserve = BusinessLedger.firmAccountIdAt(id, 1);
            var key = ApiTokens.issueBusiness(owner.getUsername(), owner, firm);
            String duplicate = firm + " Corporate Account";
            long checkpoint = Ledger.transactionCheckpoint();
            var response = rename(key.token(), reserve, duplicate);
            assertAll(
                    () -> assertThat(response.status()).as("%s", response.body()).isBetween(400, 499),
                    () -> assertThat(storedName(reserve)).isEqualTo("Reserve" + firm),
                    () -> assertThat(storedName(primary)).isEqualTo(duplicate),
                    () -> assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint));
        }
    }
    private static String path(int id) { return "/firms/me/accounts/" + id + "/display-name"; }
    private static RestApi.Response rename(String token, int id, String value) throws Exception {
        return RestApi.call("PATCH", path(id), token, JSON.writeValueAsString(Map.of("displayName", value)));
    }
    private static JsonNode accounts(String token) {
        var response = RestApi.call("GET", "/firms/me/accounts", token, null);
        assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
        assertThat(response.json().isArray()).isTrue();
        return response.json();
    }
    private static void row(JsonNode row, int id, String name, String balance) {
        assertThat(row.size()).isEqualTo(5);
        assertThat(row.path("accountId").asInt()).isEqualTo(id);
        assertThat(row.path("displayName").asText()).isEqualTo(name);
        assertThat(row.path("accountType").asText()).isEqualTo("BUSINESS");
        assertThat(row.path("balance").asText()).isEqualTo(balance);
        assertThat(row.path("archived").asBoolean()).isFalse();
    }
    private static String storedName(int id) throws Exception {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT display_name FROM accounts WHERE account_id = ?")) {
            q.setInt(1, id);
            try (var r = q.executeQuery()) { assertThat(r.next()).isTrue(); return r.getString(1); }
        }
    }
    private static void setup(MockPlayer player, String command) {
        Ops.run(player.getUsername(), () -> { player.chatCommand(command); Barrier.settle(); });
    }
}
