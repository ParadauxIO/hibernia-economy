package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FirmRolePermissionPropagationTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"business", "firm", "company", "db", "democracybusiness"})
    void editingExistingRolePermissionsUpdatesMetadataAndBothAccounts(String root) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("RpOwn"));
             MockPlayer employee = MockPlayer.connectLocal(Usernames.fresh("RpEmp"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(employee.getUuid(), TIMEOUT);
            String firm = Usernames.fresh("RpFrm");
            admin(owner, "/" + root + " create " + firm);
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            admin(owner, "/" + root + " account create " + firm + " Reserve" + firm);
            int primary = BusinessLedger.firmDefaultAccountId(id).orElseThrow();
            int reserve = BusinessLedger.firmAccountIdAt(id, 1);
            admin(owner, "/" + root + " attribute set hq " + firm + " original499");
            admin(owner, "/" + root + " attribute set discord " + firm + " https://discord.gg/original499");
            admin(owner, "/" + root + " offer " + firm + " " + employee.getUsername());
            admin(employee, "/" + root + " offer accept " + firm);
            BusinessLedger.waitForEmployeeRole(id, employee.getUuid(), "Employee", TIMEOUT);
            Perms.grant(employee.getUsername(), "business.attribute.hq");
            Perms.grant(employee.getUsername(), "business.attribute.discord");
            long checkpoint = Ledger.transactionCheckpoint();
            accessCounts(primary, reserve, owner, employee, false);
            assertThat(BusinessLedger.rolePermissionCount(id, "Employee")).isEqualTo(1);
            rejectBoth(employee, root, firm, id, "original499", "original499");
            admin(owner, "/" + root + " staff role permission add " + firm + " Employee ADMIN");
            assertThat(BusinessLedger.rolePermissionCount(id, "Employee")).isEqualTo(2);
            accessCounts(primary, reserve, owner, employee, true);
            BusinessLedger.waitForEmployeeRole(id, employee.getUuid(), "Employee", TIMEOUT);
            assertThat(command(employee, "/" + root + " attribute set hq " + id + " delegated499"))
                    .anyMatch(m -> m.contains("Successfully set"));
            assertThat(command(employee, "/" + root + " attribute set discord " + id + " https://discord.gg/delegated499"))
                    .anyMatch(m -> m.contains("Successfully set"));
            metadata(id, "delegated499", "delegated499");
            admin(owner, "/" + root + " staff role permission remove " + firm + " Employee ADMIN");
            assertThat(BusinessLedger.rolePermissionCount(id, "Employee")).isEqualTo(1);
            accessCounts(primary, reserve, owner, employee, false);
            BusinessLedger.waitForEmployeeRole(id, employee.getUuid(), "Employee", TIMEOUT);
            rejectBoth(employee, root, firm, id, "delegated499", "delegated499");
            // Server nodes and the assigned role remain unchanged; ADMIN removal denies access.
            assertThat(Perms.check(employee.getUsername(), "business.attribute.hq")).contains("has=true");
            assertThat(Perms.check(employee.getUsername(), "business.attribute.discord")).contains("has=true");
            assertThat(BusinessLedger.firmProprietor(id)).hasValue(owner.getUuid());
            assertThat(BusinessLedger.firmDefaultBalance(id).orElseThrow()).isEqualByComparingTo("0");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(employee.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
        }
    }

    private static void accessCounts(int primary, int reserve, MockPlayer owner, MockPlayer employee, boolean delegated) {
        int expected = delegated ? 2 : 1;
        for (int account : List.of(primary, reserve)) {
            Ledger.waitForMemberCount(account, expected, TIMEOUT);
            Ledger.waitForAuthorizerCount(account, expected, TIMEOUT);
            assertThat(BusinessLedger.isAccountMember(account, owner.getUuid())).isTrue();
            assertThat(BusinessLedger.isAccountAuthorizer(account, owner.getUuid())).isTrue();
            assertThat(BusinessLedger.isAccountMember(account, employee.getUuid())).isEqualTo(delegated);
            assertThat(BusinessLedger.isAccountAuthorizer(account, employee.getUuid())).isEqualTo(delegated);
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("0");
        }
    }
    private static void rejectBoth(MockPlayer employee, String root, String firm, int id, String hq, String invite) {
        for (String suffix : List.of("hq " + firm + " forbidden499", "discord " + firm + " https://discord.gg/forbidden499")) {
            var messages = command(employee, "/" + root + " attribute set " + suffix);
            assertThat(messages).anyMatch(m -> m.contains("You do not have permission to run this command."));
            assertThat(messages).noneMatch(m -> m.contains("Successfully set"));
            metadata(id, hq, invite);
        }
    }
    private static void metadata(int id, String hq, String invite) {
        assertThat(BusinessLedger.firmHqRegion(id)).hasValue(hq);
        assertThat(BusinessLedger.firmDiscordUrl(id)).hasValue("https://discord.gg/" + invite);
    }
    private static void admin(MockPlayer player, String command) {
        Ops.run(player.getUsername(), () -> command(player, command));
    }
    private static List<String> command(MockPlayer player, String command) {
        player.drainMessages(); player.chatCommand(command); Barrier.settle();
        return player.collectedMessages();
    }
}
