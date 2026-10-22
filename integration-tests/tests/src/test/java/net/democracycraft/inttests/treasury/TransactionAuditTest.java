package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class TransactionAuditTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @CsvSource({"transactions,false", "txns,false", "transactions,true", "txns,true"})
    void auditReadsOtherAccountRecordsDurableAccessAndStopsAfterRevocation(String root, boolean byId) throws Exception {
        try (MockPlayer sender = MockPlayer.connectLocal(Usernames.fresh("Src"));
             MockPlayer recipient = MockPlayer.connectLocal(Usernames.fresh("Dst"));
             MockPlayer auditor = MockPlayer.connectLocal(Usernames.fresh("Aud"))) {
            Ledger.waitForPersonalBalance(sender.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(recipient.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(auditor.getUuid(), TIMEOUT);
            int account = Ledger.personalAccountId(sender.getUuid()).orElseThrow();
            long checkpoint = Ledger.transactionCheckpoint();
            sender.chatCommand("/paya player " + recipient.getUsername() + " 23.45 AuditMemo499");
            Ledger.waitForPersonalBalance(sender.getUuid(), new java.math.BigDecimal("9976.55"), TIMEOUT);
            Barrier.settle();
            var postings = Ledger.initiatedPostingsAfter(sender.getUuid(), checkpoint);
            assertThat(postings).hasSize(2);
            long transaction = postings.get(0).transactionId();
            String route = byId ? "auditaccount " + account : "audit " + sender.getUsername();
            String canonical = "/transactions " + route;
            Perms.grant(auditor.getUsername(), "treasury.transactions");
            Perms.grant(auditor.getUsername(), "treasury.transactions.audit");
            assertThat(auditRows(auditor.getUuid())).isEmpty();
            for (String suffix : new String[]{"", " 1"}) {
                auditor.drainMessages();
                auditor.chatCommand("/" + root + " " + route + suffix);
                assertThat(auditor.awaitMessageContaining("Transactions", TIMEOUT)).contains("Page 1/1");
                Barrier.settle();
                var lines = auditor.collectedMessages().stream().filter(m -> m.startsWith("#")).toList();
                assertThat(lines).hasSize(2);
                assertThat(lines.get(0)).startsWith("#" + transaction + " ").contains("-$23.45", "AuditMemo499", sender.getUsername(), recipient.getUsername());
                assertThat(lines.get(1)).contains("+$10,000.00");
            }
            var expected = List.of("minecraft:" + auditor.getUuid(), auditor.getUsername(), "treasury.transactions.audit",
                    "CMD", canonical, "account", String.valueOf(account), "200", auditor.getUuid().toString().replace("-", ""));
            assertThat(auditRows(auditor.getUuid())).containsExactly(expected, expected);
            auditor.drainMessages();
            auditor.chatCommand("/" + root + " " + route + " 2");
            auditor.awaitMessageContaining("No transactions found for", TIMEOUT);
            Barrier.settle();
            assertThat(auditor.collectedMessages()).noneMatch(m -> m.startsWith("#"));
            var pageTwo = new ArrayList<>(expected);
            pageTwo.set(4, canonical + " (page 2)");
            assertThat(auditRows(auditor.getUuid())).containsExactly(expected, expected, pageTwo);
            RconClient.send("lp user " + auditor.getUsername() + " permission set treasury.transactions.audit false");
            Await.until("audit permission revoked", () -> Perms.check(auditor.getUsername(), "treasury.transactions.audit"),
                    r -> r.contains("has=false"), TIMEOUT);
            auditor.drainMessages();
            auditor.chatCommand("/" + root + " " + route);
            Barrier.settle();
            assertThat(auditor.collectedMessages()).noneMatch(m -> m.contains("AuditMemo499") || m.startsWith("#") || m.contains("Transactions"));
            assertThat(auditRows(auditor.getUuid())).containsExactly(expected, expected, pageTwo);
            assertThat(Ledger.initiatedPostingsAfter(sender.getUuid(), checkpoint)).isEqualTo(postings);
            assertThat(Ledger.personalBalance(sender.getUuid()).orElseThrow()).isEqualByComparingTo("9976.55");
            assertThat(Ledger.personalBalance(recipient.getUuid()).orElseThrow()).isEqualByComparingTo("10023.45");
            assertThat(Ledger.personalBalance(auditor.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"transactions", "txns"})
    void missingAuditTargetsDoNotCreateAccountsOrSuccessfulAuditRecords(String root) throws Exception {
        try (MockPlayer auditor = MockPlayer.connectLocal(Usernames.fresh("Aud"))) {
            Ledger.waitForPersonalBalance(auditor.getUuid(), TIMEOUT);
            Perms.grant(auditor.getUsername(), "treasury.transactions.audit");
            String missing = Usernames.fresh("Miss");
            UUID missingUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + missing).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(Ledger.personalAccountId(missingUuid)).isEmpty();
            long checkpoint = Ledger.transactionCheckpoint();
            auditor.drainMessages();
            auditor.chatCommand("/" + root + " audit " + missing);
            assertThat(auditor.awaitMessageContaining("Invalid target:", TIMEOUT)).contains(missing);
            Barrier.settle();
            assertThat(Ledger.personalAccountId(missingUuid)).isEmpty();
            assertThat(auditRows(auditor.getUuid())).isEmpty();
            auditor.drainMessages();
            auditor.chatCommand("/" + root + " auditaccount 2147483647");
            auditor.awaitMessageContaining("No account with that ID", TIMEOUT);
            Barrier.settle();
            assertThat(auditRows(auditor.getUuid())).isEmpty();
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            assertThat(Ledger.personalBalance(auditor.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
        }
    }

    private static List<List<String>> auditRows(UUID actor) throws Exception {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT actor_sub,actor_name,actor_role,method,path,target_type,target_id,outcome,LOWER(HEX(actor_uuid_bin)) FROM explorer_audit WHERE actor_sub=? ORDER BY audit_id")) {
            q.setString(1, "minecraft:" + actor);
            try (var r = q.executeQuery()) {
                List<List<String>> rows = new ArrayList<>();
                while (r.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= 9; i++) row.add(r.getString(i));
                    rows.add(row);
                }
                return rows;
            }
        }
    }
}
