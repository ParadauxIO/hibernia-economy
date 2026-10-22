package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Production scheduler + Business API; SQL configures bindings and observes publication only. */
@EnabledIfEnvironmentVariable(named = "IT_GROUPS_PROFILE", matches = "true")
class DerivedGroupLifecycleTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Test
    void scheduledBusinessMembershipTracksOfflineRolesAndArchiveWithoutRemovingManualGrants() throws Exception {
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("DgOwn"));
             var employee = MockPlayer.connectLocal(Usernames.fresh("DgEmp"));
             var outsider = MockPlayer.connectLocal(Usernames.fresh("DgOut"))) {
            for (var p : List.of(owner, employee, outsider)) Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            String firm = Usernames.fresh("DgFrm");
            command(owner, "/business create " + firm, "Successfully created");
            int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
            command(owner, "/business offer " + firm + " " + employee.getUsername(), "offer");
            Ops.run(employee.getUsername(), () -> {
                employee.chatCommand("/business offer accept " + firm);
                BusinessLedger.waitForEmployeeRole(firmId, employee.getUuid(), "Employee", TIMEOUT);
            });
            int group = createGroup();
            try {
                sql("INSERT INTO explorer_group_member(group_id,player_uuid_bin,source) VALUES (?,UUID_TO_BIN(?),'manual')", group, outsider.getUuid().toString());
                sql("INSERT INTO explorer_group_sync(group_id,source,selector) VALUES (?,'business',?)", group, "" + firmId);
                // A missing optional Realty provider must not prevent Business publication.
                sql("INSERT INTO explorer_group_sync(group_id,source,selector) VALUES (?,'realty',?)", group, "00000000-0000-0000-0000-000000000001/missing");
                long checkpoint = Ledger.transactionCheckpoint();
                members(group, Set.of(owner.getUuid()));
                command(owner, "/business staff role permission add " + firm + " Employee FINANCIAL", "permission");
                members(group, Set.of(owner.getUuid(), employee.getUuid()));
                employee.close();
                Await.until("employee offline", () -> Perms.check(employee.getUsername(), "business.create"), "offline"::equals, TIMEOUT);
                Timestamp prior = refreshed(group, employee.getUuid());
                Await.until("offline employee refreshed by another scheduled pass", () -> refreshed(group, employee.getUuid()), t -> t.after(prior), TIMEOUT);
                command(owner, "/business staff role permission remove " + firm + " Employee FINANCIAL", "permission");
                members(group, Set.of(owner.getUuid()));
                assertThat(readMembers(group, "manual")).containsExactly(outsider.getUuid());
                command(owner, "/business staff role permission add " + firm + " Employee ADMIN", "permission");
                members(group, Set.of(owner.getUuid(), employee.getUuid()));
                command(owner, "/business admin disband " + firmId, "has been disbanded");
                members(group, Set.of());
                assertThat(readMembers(group, "manual")).containsExactly(outsider.getUuid());
                assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
                for (var p : List.of(owner, employee, outsider))
                    assertThat(Ledger.personalBalance(p.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            } finally {
                sql("DELETE FROM explorer_group WHERE group_id=?", group);
            }
        }
    }
    private static void command(MockPlayer player, String command, String response) {
        Ops.run(player.getUsername(), () -> {
            player.drainMessages(); player.chatCommand(command);
            player.awaitMessageContaining(response, TIMEOUT); Barrier.settle();
        });
    }
    private static int createGroup() throws SQLException {
        try (var c = connection(); var q = c.prepareStatement("INSERT INTO explorer_group(name) VALUES (?)", Statement.RETURN_GENERATED_KEYS)) {
            q.setString(1, Usernames.fresh("DGrp")); q.executeUpdate();
            try (var r = q.getGeneratedKeys()) { assertThat(r.next()).isTrue(); return r.getInt(1); }
        }
    }
    private static void members(int group, Set<UUID> expected) {
        Await.until("derived Business membership " + expected, () -> readMembers(group, "business"), expected::equals, TIMEOUT);
    }
    private static Set<UUID> readMembers(int group, String source) {
        try (var c = connection(); var q = c.prepareStatement("SELECT BIN_TO_UUID(player_uuid_bin) FROM explorer_group_member WHERE group_id=? AND source=?")) {
            q.setInt(1, group); q.setString(2, source);
            Set<UUID> result = new HashSet<>();
            try (var r = q.executeQuery()) { while (r.next()) result.add(UUID.fromString(r.getString(1))); }
            return result;
        } catch (SQLException e) { throw new IllegalStateException("read group members", e); }
    }
    private static Timestamp refreshed(int group, UUID player) {
        try (var c = connection(); var q = c.prepareStatement("SELECT synced_at FROM explorer_group_member WHERE group_id=? AND player_uuid_bin=UUID_TO_BIN(?) AND source='business'")) {
            q.setInt(1, group); q.setString(2, player.toString());
            try (var r = q.executeQuery()) { assertThat(r.next()).isTrue(); return r.getTimestamp(1); }
        } catch (SQLException e) { throw new IllegalStateException("read group refresh", e); }
    }
    private static void sql(String sql, Object... args) throws SQLException {
        try (var c = connection(); var q = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) q.setObject(i + 1, args[i]);
            q.executeUpdate();
        }
    }
    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
    }
}
