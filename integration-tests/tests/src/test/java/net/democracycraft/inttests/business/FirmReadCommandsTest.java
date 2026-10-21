package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FirmReadCommandsTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"business", "firm", "company", "db", "democracybusiness"})
    void infoShowsExactMetadataAggregateBalanceAndDefunctState(String root) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("FIOwn"));
             MockPlayer reader = MockPlayer.connectLocal(Usernames.fresh("FIRe"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(reader.getUuid(), TIMEOUT);
            String firm = Usernames.fresh("FInfo");
            admin(owner, "/" + root + " create " + firm);
            int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
            int primary = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();
            admin(owner, "/" + root + " account create " + firm + " Reserve");
            int reserve = BusinessLedger.firmAccountIdAt(firmId, 1);
            admin(owner, "/paya account " + primary + " 23.45 InfoPrimary");
            admin(owner, "/paya account " + reserve + " 11 InfoReserve");
            Perms.grant(reader.getUsername(), "business.info");
            long checkpoint = Ledger.transactionCheckpoint();
            var initial = command(reader, "/" + root + " info " + firm);
            assertInfo(initial, firm, firmId, owner.getUsername(), "$34.45", "N/A", "N/A", false);
            String url = "https://discord.gg/par499";
            admin(owner, "/" + root + " attribute set hq " + firm + " plot499");
            admin(owner, "/" + root + " attribute set discord " + firm + " " + url);
            var changed = command(reader, "/" + root + " info " + firm);
            assertInfo(changed, firm, firmId, owner.getUsername(), "$34.45", "plot499", url, false);
            assertThat(command(reader, "/" + root + " info " + firmId)).isEqualTo(changed);
            assertThat(BusinessLedger.firmHqRegion(firmId)).hasValue("plot499");
            assertThat(BusinessLedger.firmDiscordUrl(firmId)).hasValue(url);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("9965.55");
            admin(owner, "/" + root + " admin disband " + firm);
            assertInfo(command(reader, "/" + root + " info " + firmId), firm, firmId,
                    owner.getUsername(), "$0.00", "plot499", url, true);
            long afterDisband = Ledger.transactionCheckpoint();
            deny(reader, "business.info");
            assertThat(command(reader, "/" + root + " info " + firm)).noneMatch(m -> m.contains("Firm Information") || m.contains("Balance:") || m.contains(url));
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(afterDisband);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(reader.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"business", "firm", "company", "db", "democracybusiness"})
    void listsTrackEmploymentAndDistinguishDefunctOwnedFromGlobalFirms(String root) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("FLOwn"));
             MockPlayer employee = MockPlayer.connectLocal(Usernames.fresh("FLEmp"));
             MockPlayer reader = MockPlayer.connectLocal(Usernames.fresh("FLRe"))) {
            for (MockPlayer p : List.of(owner, employee, reader)) {
                Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
                Perms.grant(p.getUsername(), "business.list");
            }
            Perms.grant(reader.getUsername(), "business.list.other");
            Perms.grant(reader.getUsername(), "business.list.all");
            String firm = Usernames.fresh("FList");
            admin(owner, "/" + root + " create " + firm);
            int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
            long checkpoint = Ledger.transactionCheckpoint();
            String row = "1. " + firm + " owned by " + owner.getUsername() + " (" + firmId + ")";
            assertThat(rows(command(owner, "/" + root + " list"))).containsExactly(row);
            assertEmpty(command(reader, "/" + root + " list"), reader.getUsername());
            admin(owner, "/" + root + " offer " + firm + " " + employee.getUsername());
            admin(employee, "/" + root + " offer accept " + firm);
            BusinessLedger.waitForEmployeeRole(firmId, employee.getUuid(), "Employee", TIMEOUT);
            assertThat(rows(command(employee, "/" + root + " list"))).containsExactly(row);
            var other = command(reader, "/" + root + " list " + employee.getUsername());
            assertThat(other).anyMatch(m -> m.contains("Firms for " + employee.getUsername() + ":"));
            assertThat(rows(other)).containsExactly(row);
            var all = command(reader, "/" + root + " list all");
            assertThat(rows(all)).startsWith(row);
            assertThat(command(reader, "/" + root + " list all 1")).isEqualTo(all);
            admin(owner, "/" + root + " fire " + firm + " " + employee.getUsername());
            BusinessLedger.waitForEmployeeCount(firmId, 0, TIMEOUT);
            assertEmpty(command(employee, "/" + root + " list"), employee.getUsername());
            assertEmpty(command(reader, "/" + root + " list " + employee.getUsername()), employee.getUsername());
            admin(owner, "/" + root + " admin disband " + firm);
            var defunctRows = rows(command(owner, "/" + root + " list"));
            assertThat(command(reader, "/" + root + " list all")).noneMatch(m -> m.contains(firm));
            deny(reader, "business.list.other");
            var deniedOther = command(reader, "/" + root + " list " + owner.getUsername());
            assertThat(rows(deniedOther)).isEmpty();
            assertThat(deniedOther).noneMatch(m -> m.contains("Firms for"));
            deny(reader, "business.list.all");
            assertThat(rows(command(reader, "/" + root + " list all"))).isEmpty();
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            for (MockPlayer p : List.of(owner, employee, reader)) {
                assertThat(Ledger.personalBalance(p.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            }
            // Keep the rendering regression after the independent state/access checks.
            assertThat(defunctRows)
                    .containsExactly("1. " + firm + " owned by " + owner.getUsername() + " (Defunct)");
        }
    }

    private static void assertInfo(List<String> messages, String firm, int id, String owner, String balance, String hq, String discord, boolean defunct) {
        String text = String.join("\n", messages);
        assertThat(text).contains("Firm Information for " + firm);
        assertThat(text.lines().toList()).contains("Firm ID: " + id, "Owner: " + owner,
                "Balance: " + balance, "HQ Plot: " + hq, "Discord URL: " + discord);
        assertThat(text.contains("(Defunct)")).isEqualTo(defunct);
    }

    private static void assertEmpty(List<String> messages, String username) {
        assertThat(messages).anyMatch(m -> m.contains(username + " neither owns a firm nor is employed by a firm."));
        assertThat(rows(messages)).isEmpty();
    }

    private static List<String> rows(List<String> messages) {
        return messages.stream().filter(m -> m.matches("\\d+\\. .* owned by .*" )).toList();
    }

    private static void deny(MockPlayer player, String node) {
        RconClient.send("lp user " + player.getUsername() + " permission set " + node + " false");
        Await.until("permission revoked: " + node, () -> Perms.check(player.getUsername(), node), r -> r.contains("has=false"), TIMEOUT);
    }

    private static void admin(MockPlayer player, String command) {
        Ops.run(player.getUsername(), () -> command(player, command));
    }

    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages(); player.chatCommand(command); Barrier.settle();
        return player.collectedMessages();
    }
}
