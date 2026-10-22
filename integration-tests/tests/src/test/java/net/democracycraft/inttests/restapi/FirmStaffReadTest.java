package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FirmStaffReadTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void readsFollowLiveEmploymentAndRoleEditsWithoutLeakingAcrossScopes() throws Exception {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("FsOwn"));
             MockPlayer first = MockPlayer.connectLocal(Usernames.fresh("FsOne"));
             MockPlayer second = MockPlayer.connectLocal(Usernames.fresh("FsTwo"));
             MockPlayer outsider = MockPlayer.connectLocal(Usernames.fresh("FsOut"))) {
            for (MockPlayer p : List.of(owner, first, second, outsider)) Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            String firm = Usernames.fresh("FsFrm"), foreign = Usernames.fresh("FsFor");
            setup(owner, "/business create " + firm);
            setup(outsider, "/business create " + foreign);
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int primary = BusinessLedger.firmDefaultAccountId(id).orElseThrow();
            var key = ApiTokens.issueBusiness(owner.getUsername(), owner, firm);
            var scoped = ApiTokens.issueBusinessAccount(owner, firm, primary);
            var other = ApiTokens.issueBusiness(outsider.getUsername(), outsider, foreign);
            var personal = ApiTokens.issuePersonal(outsider.getUsername(), outsider);
            String token = key.token();
            long checkpoint = Ledger.transactionCheckpoint();
            assertThat(get(token, "employees").size()).isZero();
            JsonNode defaults = get(token, "roles");
            assertThat(defaults.size()).isEqualTo(5);
            String[] names = {"Proprietor", "Co-Proprietor", "Manager", "Supervisor", "Employee"};
            String[] permissions = {"ADMIN", "ADMIN", "FINANCIAL", "CHESTSHOP", "DEFAULT"};
            for (int i = 0; i < 5; i++) role(defaults.get(i), names[i], i + 1, permissions[i]);
            for (String deniedToken : new String[]{scoped.token(), personal.token(), null}) {
                for (String route : List.of("employees", "roles")) {
                    var response = RestApi.call("GET", "/firms/me/" + route, deniedToken, null);
                    assertThat(response.status()).isEqualTo(deniedToken == null ? 401 : 403);
                    assertThat(response.body()).doesNotContain(first.getUsername(), second.getUsername());
                }
            }
            for (MockPlayer hire : List.of(first, second)) {
                setup(owner, "/business offer " + firm + " " + hire.getUsername());
                setup(hire, "/business offer accept " + firm);
                BusinessLedger.waitForEmployeeRole(id, hire.getUuid(), "Employee", TIMEOUT);
            }
            JsonNode hired = get(token, "employees");
            assertThat(hired.size()).isEqualTo(2);
            for (MockPlayer hire : List.of(first, second)) {
                JsonNode row = java.util.stream.StreamSupport.stream(hired.spliterator(), false)
                        .filter(n -> n.path("playerUuid").asText().equals(hire.getUuid().toString())).findFirst().orElseThrow();
                employee(row, hire, "Employee", joinedAt(id, hire));
            }
            setup(owner, "/business staff promote " + firm + " " + second.getUsername());
            BusinessLedger.waitForEmployeeRole(id, second.getUuid(), "Supervisor", TIMEOUT);
            JsonNode promoted = get(token, "employees");
            assertThat(promoted.size()).isEqualTo(2);
            employee(promoted.get(0), second, "Supervisor", joinedAt(id, second));
            employee(promoted.get(1), first, "Employee", joinedAt(id, first));
            setup(owner, "/business staff role create " + firm + " Auditor 6");
            JsonNode added = get(token, "roles");
            assertThat(added.size()).isEqualTo(6);
            role(added.get(5), "Auditor", 6, "DEFAULT");
            setup(owner, "/business staff role permission add " + firm + " Auditor FINANCIAL");
            role(get(token, "roles").get(5), "Auditor", 6, "DEFAULT", "FINANCIAL");
            setup(owner, "/business staff role permission remove " + firm + " Auditor FINANCIAL");
            role(get(token, "roles").get(5), "Auditor", 6, "DEFAULT");
            setup(owner, "/business staff role rename " + firm + " Auditor Reviewer");
            role(get(token, "roles").get(5), "Reviewer", 6, "DEFAULT");
            assertThat(get(other.token(), "roles")).isEqualTo(defaults);
            assertThat(get(other.token(), "employees").size()).isZero();
            setup(owner, "/business staff role delete " + firm + " Reviewer");
            assertThat(get(token, "roles")).isEqualTo(defaults);
            setup(owner, "/business fire " + firm + " " + first.getUsername());
            JsonNode remaining = get(token, "employees");
            assertThat(remaining.size()).isEqualTo(1);
            employee(remaining.get(0), second, "Supervisor", joinedAt(id, second));
            setup(owner, "/business fire " + firm + " " + second.getUsername());
            assertThat(get(token, "employees").size()).isZero();
            assertThat(BusinessLedger.activeFirmAccountCount(id)).isEqualTo(1);
            assertThat(Ledger.accountBalanceById(primary).orElseThrow()).isEqualByComparingTo("0");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            for (MockPlayer p : List.of(owner, first, second, outsider)) {
                assertThat(Ledger.personalBalance(p.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            }
        }
    }
    private static void employee(JsonNode row, MockPlayer player, String role, Instant joinedAt) {
        assertThat(row.size()).isEqualTo(4);
        assertThat(row.path("playerUuid").asText()).isEqualTo(player.getUuid().toString());
        assertThat(row.path("playerName").asText()).isEqualTo(player.getUsername());
        assertThat(row.path("roleName").asText()).isEqualTo(role);
        assertThat(Instant.parse(row.path("joinedAt").asText())).isEqualTo(joinedAt);
    }
    private static void role(JsonNode row, String name, int rank, String... permissions) {
        assertThat(row.size()).isEqualTo(5);
        assertThat(row.path("name").asText()).isEqualTo(name);
        assertThat(row.path("rankOrder").asInt()).isEqualTo(rank);
        assertThat(row.path("proprietorLike").isBoolean()).isTrue();
        assertThat(row.path("defaultRole").isBoolean()).isTrue();
        assertThat(java.util.stream.StreamSupport.stream(row.path("permissions").spliterator(), false)
                .map(JsonNode::asText).toList()).containsExactlyInAnyOrder(permissions);
    }
    private static Instant joinedAt(int firm, MockPlayer player) throws Exception {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT joined_at FROM firm_employee WHERE firm_id = ? AND player_uuid_bin = UNHEX(REPLACE(?, '-', '')) AND left_at IS NULL")) {
            q.setInt(1, firm); q.setString(2, player.getUuid().toString());
            try (var r = q.executeQuery()) { assertThat(r.next()).isTrue(); return r.getTimestamp(1).toInstant(); }
        }
    }
    private static JsonNode get(String token, String route) {
        var response = RestApi.call("GET", "/firms/me/" + route, token, null);
        assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
        assertThat(response.json().isArray()).isTrue();
        return response.json();
    }
    private static void setup(MockPlayer player, String command) {
        Ops.run(player.getUsername(), () -> { player.chatCommand(command); Barrier.settle(); });
    }
}
