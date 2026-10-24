package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FirmNameReleaseTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"business", "firm", "company", "db", "democracybusiness"})
    void releasedNameCanBeReusedWithoutReusingArchivedIdentityOrMoney(String root) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("NrOwn"));
             MockPlayer successor = MockPlayer.connectLocal(Usernames.fresh("NrNew"));
             MockPlayer admin = MockPlayer.connectLocal(Usernames.fresh("NrAdm"))) {
            for (MockPlayer p : List.of(owner, successor, admin)) Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            String name = Usernames.fresh("NrFrm");
            setup(owner, "/" + root + " create " + name);
            int oldId = BusinessLedger.waitForFirm(name, TIMEOUT);
            int oldAccount = BusinessLedger.firmDefaultAccountId(oldId).orElseThrow();
            setup(owner, "/paya account " + oldAccount + " 23.45 BeforeRelease");
            Perms.grant(admin.getUsername(), "business.admin.releasename");
            assertThat(command(admin, "/" + root + " admin releasename " + oldId))
                    .anyMatch(m -> m.contains("Only disbanded firms can have their names released."));
            flags(oldId, false, false);
            assertThat(Ledger.accountBalanceById(oldAccount).orElseThrow()).isEqualByComparingTo("23.45");
            setup(owner, "/" + root + " admin disband " + name);
            flags(oldId, true, false);
            assertThat(BusinessLedger.firmDefaultAccountId(oldId)).isEmpty();
            var oldHistory = Ledger.initiatedPostingsAfter(owner.getUuid(), 0);
            long checkpoint = Ledger.transactionCheckpoint();
            var reserved = setup(successor, "/" + root + " create " + name);
            assertThat(reserved).anyMatch(m -> m.contains("already taken"));
            assertThat(BusinessLedger.firmId(name)).isEmpty();
            revoke(admin);
            assertThat(command(admin, "/" + root + " admin releasename " + oldId))
                    .contains("You don't have permission to do that.");
            flags(oldId, true, false);
            Perms.grant(admin.getUsername(), "business.admin.releasename");
            assertThat(command(admin, "/" + root + " admin releasename " + oldId))
                    .anyMatch(m -> m.contains("Released the name " + name + " (firm #" + oldId + ") for reuse."));
            flags(oldId, true, true);
            assertThat(command(admin, "/" + root + " admin releasename " + oldId))
                    .anyMatch(m -> m.contains("Firm name is already released"));
            setup(successor, "/" + root + " create " + name);
            int newId = BusinessLedger.waitForFirm(name, TIMEOUT);
            assertThat(newId).isNotEqualTo(oldId);
            int newAccount = BusinessLedger.firmDefaultAccountId(newId).orElseThrow();
            assertThat(newAccount).isNotEqualTo(oldAccount);
            flags(oldId, true, true);
            flags(newId, false, false);
            assertThat(BusinessLedger.firmDisplayName(oldId)).hasValue(name);
            assertThat(BusinessLedger.firmDisplayName(newId)).hasValue(name);
            assertThat(BusinessLedger.firmProprietor(oldId)).hasValue(owner.getUuid());
            assertThat(BusinessLedger.firmProprietor(newId)).hasValue(successor.getUuid());
            assertThat(BusinessLedger.activeFirmAccountCount(oldId)).isZero();
            assertThat(BusinessLedger.firmAccountCount(oldId)).isEqualTo(1);
            assertThat(BusinessLedger.activeFirmAccountCount(newId)).isEqualTo(1);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            Perms.grant(admin.getUsername(), "business.info");
            assertThat(command(admin, "/" + root + " info " + name)).anyMatch(m -> m.contains("Firm ID: " + newId));
            assertThat(command(admin, "/" + root + " info " + oldId)).anyMatch(m -> m.contains("Firm ID: " + oldId) && m.contains("(Defunct)"));
            setup(successor, "/paya business " + name + " 7.89 AfterReuse");
            assertThat(Ledger.accountBalanceById(newAccount).orElseThrow()).isEqualByComparingTo("7.89");
            assertThat(Ledger.accountBalanceById(oldAccount).orElseThrow()).isEqualByComparingTo("0");
            assertThat(Ledger.postingsCountForAccount(oldAccount)).isEqualTo(2);
            assertThat(Ledger.initiatedPostingsAfter(owner.getUuid(), 0)).isEqualTo(oldHistory);
            assertThat(Ledger.initiatedPostingsAfter(successor.getUuid(), checkpoint)).hasSize(2);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(successor.getUuid()).orElseThrow()).isEqualByComparingTo("9992.11");
            assertThat(Ledger.personalBalance(admin.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
        }
    }
    private static void flags(int id, boolean archived, boolean released) {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT is_archived, name_released FROM firm WHERE firm_id = ?")) {
            q.setInt(1, id);
            try (var r = q.executeQuery()) {
                assertThat(r.next()).isTrue();
                assertThat(r.getBoolean(1)).isEqualTo(archived);
                assertThat(r.getBoolean(2)).isEqualTo(released);
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }
    private static void revoke(MockPlayer player) {
        RconClient.send("lp user " + player.getUsername() + " permission set business.admin.releasename false");
        Await.until("release permission revoked", () -> Perms.check(player.getUsername(), "business.admin.releasename"), r -> r.contains("has=false"), TIMEOUT);
    }
    private static List<String> setup(MockPlayer player, String command) {
        var result = new java.util.concurrent.atomic.AtomicReference<List<String>>();
        Ops.run(player.getUsername(), () -> result.set(command(player, command)));
        return result.get();
    }
    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages(); player.chatCommand(command); Barrier.settle();
        return player.collectedMessages();
    }
}
