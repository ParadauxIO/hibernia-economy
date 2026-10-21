package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.net.http.*;
import java.sql.DriverManager;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "BYTEBIN_URL", matches = ".+")
class TransactionExportTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String BASE = System.getenv("BYTEBIN_URL");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"transactions", "txns"})
    void exportContainsExactOwnPostingsEnforcesMembershipAndRecoversFromUploadFailure(String root) throws Exception {
        post("__reset", "{}");
        try (MockPlayer sender = MockPlayer.connectLocal(Usernames.fresh("Exp"));
             MockPlayer recipient = MockPlayer.connectLocal(Usernames.fresh("Dst"))) {
            Ledger.waitForPersonalBalance(sender.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(recipient.getUuid(), TIMEOUT);
            int account = Ledger.personalAccountId(sender.getUuid()).orElseThrow();
            int otherAccount = Ledger.personalAccountId(recipient.getUuid()).orElseThrow();
            Perms.grant(sender.getUsername(), "treasury.transactions.export");
            Perms.grant(sender.getUsername(), "treasury.transactions.audit");
            Perms.grant(sender.getUsername(), "griefprevention.spam");
            long checkpoint = Ledger.transactionCheckpoint();
            var key = ApiTokens.issuePersonal(sender.getUsername(), sender);
            String memo = "ExportMemo, \"quoted\"";
            var transfer = RestApi.postTransfer(key.token(), JSON.writeValueAsString(java.util.Map.of(
                    "toAccountId", otherAccount, "amount", "23.45", "memo", memo)), null);
            assertThat(transfer.status()).isEqualTo(200);
            assertThat(transfer.json().path("memo").asText()).isEqualTo(memo);
            Ledger.waitForPersonalBalance(sender.getUuid(), new java.math.BigDecimal("9976.55"), TIMEOUT);
            Barrier.settle();
            var postings = Ledger.initiatedPostingsAfter(sender.getUuid(), checkpoint);
            assertThat(postings).hasSize(2);
            long transaction = postings.get(0).transactionId();
            String csv = export(sender, root + " export");
            String[] rows = csv.split("\n");
            assertThat(rows).hasSize(3);
            assertThat(rows[0]).isEqualTo("posting_id,txn_id,account_id,amount,memo,settlement_time,message,initiator_uuid,authorizer_uuid,plugin_system");
            assertThat(rows[1]).startsWith(postingId(transaction, account) + "," + transaction + "," + account + ",-23.45,");
            String quotedMemo = "\"ExportMemo, \"\"quoted\"\"\"";
            assertThat(rows[1]).contains(quotedMemo + ",", "," + quotedMemo + "," + sender.getUuid() + ",");
            assertThat(rows[2].split(",", 5)[2]).isEqualTo(String.valueOf(account));
            assertThat(rows[2].split(",", 5)[3]).isEqualTo("10000.00");
            JsonNode upload = captures().get(0);
            assertThat(header(upload, "Content-Encoding")).isEqualTo("gzip");
            assertThat(header(upload, "Content-Type")).isEqualTo("text/csv");
            assertThat(header(upload, "Bytebin-Max-Reads")).isEqualTo("1");
            assertThat(upload.get("content").asText()).isEqualTo(csv);
            assertThat(export(sender, root + " export " + account)).isEqualTo(csv);
            assertThat(captures().size()).isEqualTo(2);

            // Audit privilege deliberately does not grant export membership.
            sender.drainMessages();
            sender.chatCommand("/" + root + " export " + otherAccount);
            sender.awaitMessageContaining("You do not have access to that account", TIMEOUT);
            Barrier.settle();
            assertThat(sender.collectedMessages()).noneMatch(m -> m.contains("exported"));
            assertThat(captures().size()).isEqualTo(2);
            RconClient.send("lp user " + sender.getUsername() + " permission set treasury.transactions.export false");
            Await.until("export permission revoked", () -> Perms.check(sender.getUsername(), "treasury.transactions.export"),
                    r -> r.contains("has=false"), TIMEOUT);
            sender.drainMessages();
            sender.chatCommand("/" + root + " export");
            Barrier.settle();
            assertThat(sender.collectedMessages()).noneMatch(m -> m.contains("exported"));
            assertThat(captures().size()).isEqualTo(2);
            Perms.grant(sender.getUsername(), "treasury.transactions.export");
            post("__mode", "{\"status\":503}");
            sender.drainMessages();
            sender.chatCommand("/" + root + " export");
            Barrier.settle();
            assertThat(sender.collectedMessages()).noneMatch(m -> m.contains("exported"));
            assertThat(captures().size()).isEqualTo(3);
            assertThat(captures().get(2).get("status").asInt()).isEqualTo(503);
            post("__mode", "{\"status\":201}");
            assertThat(export(sender, root + " export")).isEqualTo(csv);
            assertThat(captures().size()).isEqualTo(4);
            assertThat(Ledger.initiatedPostingsAfter(sender.getUuid(), checkpoint)).isEqualTo(postings);
            assertThat(Ledger.personalBalance(sender.getUuid()).orElseThrow()).isEqualByComparingTo("9976.55");
            assertThat(Ledger.personalBalance(recipient.getUuid()).orElseThrow()).isEqualByComparingTo("10023.45");
        } finally {
            post("__mode", "{\"status\":201}");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"transactions", "txns"})
    void governmentExportRequiresMembershipAndRemovalStopsUploads(String root) throws Exception {
        post("__reset", "{}");
        try (MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("GEAdm"));
             MockPlayer reader = MockPlayer.connectLocal(Usernames.fresh("GERe"))) {
            Ledger.waitForPersonalBalance(admin.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(reader.getUuid(), TIMEOUT);
            Perms.grant(reader.getUsername(), "treasury.transactions.export");
            Perms.grant(reader.getUsername(), "treasury.transactions.audit");
            String name = Usernames.fresh("GEGov");
            adminCommand(admin, "/gov account create " + name);
            int account = Ledger.waitForGovernmentAccount(name, TIMEOUT);
            long checkpoint = Ledger.transactionCheckpoint();
            adminCommand(admin, "/paya government " + name + " 23.45 SharedExport");
            Ledger.waitForGovernmentBalance(name, new java.math.BigDecimal("23.45"), TIMEOUT);
            var postings = Ledger.initiatedPostingsAfter(admin.getUuid(), checkpoint);
            assertThat(postings).hasSize(2);
            adminCommand(admin, "/gov account viewer add " + name + " " + reader.getUsername());
            assertExportDenied(reader, root, account, 0);
            adminCommand(admin, "/gov account member add " + name + " " + reader.getUsername());
            String csv = export(reader, root + " export " + account);
            assertSharedCsv(csv, account, postings.get(0).transactionId(), admin);
            adminCommand(admin, "/gov account auth add " + name + " " + reader.getUsername());
            assertThat(export(reader, root + " export " + account)).isEqualTo(csv);
            adminCommand(admin, "/gov account member remove " + name + " " + reader.getUsername());
            assertExportDenied(reader, root, account, 2);
            reader.drainMessages();
            reader.chatCommand("/" + root + " export 2147483647");
            Barrier.settle();
            assertThat(reader.collectedMessages()).anyMatch(m -> m.contains("Account not found."));
            assertThat(captures().size()).isEqualTo(2);
            assertThat(Ledger.initiatedPostingsAfter(admin.getUuid(), checkpoint)).isEqualTo(postings);
            assertThat(Ledger.personalBalance(admin.getUuid()).orElseThrow()).isEqualByComparingTo("9976.55");
            assertThat(Ledger.personalBalance(reader.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("23.45");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"transactions", "txns"})
    void businessExportTracksFinancialRoleAndDemotionStopsUploads(String root) throws Exception {
        post("__reset", "{}");
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("BEOwn"));
             MockPlayer employee = MockPlayer.connectLocal(Usernames.fresh("BEEmp"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(employee.getUuid(), TIMEOUT);
            Perms.grant(employee.getUsername(), "treasury.transactions.export");
            Perms.grant(owner.getUsername(), "treasury.transactions.export");
            String firm = Usernames.fresh("BEFir");
            adminCommand(owner, "/business create " + firm);
            int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int account = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
            long checkpoint = Ledger.transactionCheckpoint();
            adminCommand(owner, "/paya business " + firm + " 23.45 SharedExport");
            Ledger.waitForAccountBalanceById(account, new java.math.BigDecimal("23.45"), TIMEOUT);
            var postings = Ledger.initiatedPostingsAfter(owner.getUuid(), checkpoint);
            assertThat(postings).hasSize(2);
            adminCommand(owner, "/business offer " + firm + " " + employee.getUsername());
            adminCommand(employee, "/business offer accept " + firm);
            BusinessLedger.waitForEmployeeRole(firmId, employee.getUuid(), "Employee", TIMEOUT);
            assertExportDenied(employee, root, account, 0);
            adminCommand(owner, "/business staff promote " + firm + " " + employee.getUsername());
            BusinessLedger.waitForEmployeeRole(firmId, employee.getUuid(), "Supervisor", TIMEOUT);
            adminCommand(owner, "/business staff promote " + firm + " " + employee.getUsername());
            BusinessLedger.waitForEmployeeRole(firmId, employee.getUuid(), "Manager", TIMEOUT);
            String csv = export(employee, root + " export " + account);
            assertSharedCsv(csv, account, postings.get(0).transactionId(), owner);
            assertThat(export(owner, root + " export " + account)).isEqualTo(csv);
            adminCommand(owner, "/business staff demote " + firm + " " + employee.getUsername());
            BusinessLedger.waitForEmployeeRole(firmId, employee.getUuid(), "Supervisor", TIMEOUT);
            assertExportDenied(employee, root, account, 2);
            assertThat(Ledger.initiatedPostingsAfter(owner.getUuid(), checkpoint)).isEqualTo(postings);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("9976.55");
            assertThat(Ledger.personalBalance(employee.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("23.45");
        }
    }

    private static void adminCommand(MockPlayer player, String command) {
        Ops.run(player.getUsername(), () -> {
            player.drainMessages();
            player.chatCommand(command);
            Barrier.settle();
        });
    }

    private static void assertExportDenied(MockPlayer player, String root, int account, int uploads) throws Exception {
        player.drainMessages();
        player.chatCommand("/" + root + " export " + account);
        Barrier.settle();
        assertThat(player.collectedMessages()).anyMatch(m -> m.contains("You do not have access to that account"));
        assertThat(player.collectedMessages()).noneMatch(m -> m.contains("exported"));
        assertThat(captures().size()).isEqualTo(uploads);
    }

    private static void assertSharedCsv(String csv, int account, long transaction, MockPlayer initiator) throws Exception {
        String[] rows = csv.split("\n");
        assertThat(rows).hasSize(2);
        assertThat(rows[0]).isEqualTo("posting_id,txn_id,account_id,amount,memo,settlement_time,message,initiator_uuid,authorizer_uuid,plugin_system");
        assertThat(rows[1]).startsWith(postingId(transaction, account) + "," + transaction + "," + account + ",23.45,")
                .contains("SharedExport", initiator.getUuid().toString());
    }

    private static String export(MockPlayer player, String command) throws Exception {
        player.drainMessages();
        player.chatCommand("/" + command);
        String reply = player.awaitMessageContaining("exported", TIMEOUT);
        var url = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(BASE) + "it-\\d+").matcher(reply);
        assertThat(url.find()).as("export URL uses disposable capture service: %s", reply).isTrue();
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(url.group())).timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private static JsonNode captures() throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + "__captures")).timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static void post(String path, String body) throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path)).timeout(TIMEOUT).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private static String header(JsonNode capture, String name) {
        var fields = capture.get("headers").fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue().asText();
        }
        return null;
    }

    private static long postingId(long transaction, int account) throws Exception {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT posting_id FROM ledger_postings WHERE txn_id=? AND account_id=?")) {
            q.setLong(1, transaction); q.setInt(2, account);
            try (var r = q.executeQuery()) { assertThat(r.next()).isTrue(); return r.getLong(1); }
        }
    }
}
