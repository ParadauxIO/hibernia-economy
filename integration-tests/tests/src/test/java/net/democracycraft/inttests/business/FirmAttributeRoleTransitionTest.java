package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class FirmAttributeRoleTransitionTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(strings = {"business", "firm", "company", "db", "democracybusiness"})
    void promotionAndDemotionChangeMetadataAuthorityWithoutRelogging(String root) {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("RtOwn"));
             MockPlayer employee = MockPlayer.connectLocal(Usernames.fresh("RtEmp"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(employee.getUuid(), TIMEOUT);
            String firm = Usernames.fresh("RtFrm");
            admin(owner, "/" + root + " create " + firm);
            int id = BusinessLedger.waitForFirm(firm, TIMEOUT);
            admin(owner, "/" + root + " attribute set hq " + firm + " original499");
            admin(owner, "/" + root + " attribute set discord " + firm + " https://discord.gg/original499");
            admin(owner, "/" + root + " offer " + firm + " " + employee.getUsername());
            admin(employee, "/" + root + " offer accept " + firm);
            BusinessLedger.waitForEmployeeRole(id, employee.getUuid(), "Employee", TIMEOUT);
            Perms.grant(employee.getUsername(), "business.attribute.hq");
            Perms.grant(employee.getUsername(), "business.attribute.discord");
            long checkpoint = Ledger.transactionCheckpoint();
            promote(owner, employee, root, firm, id, "Supervisor");
            promote(owner, employee, root, firm, id, "Manager");
            // FINANCIAL authority must not imply administrative metadata authority.
            rejectBoth(employee, root, firm, id, "original499", "original499");
            promote(owner, employee, root, firm, id, "Co-Proprietor");
            assertThat(command(employee, "/" + root + " attribute set hq " + id + " delegated499"))
                    .anyMatch(m -> m.contains("Successfully set"));
            assertThat(command(employee, "/" + root + " attribute set discord " + id + " https://discord.gg/delegated499"))
                    .anyMatch(m -> m.contains("Successfully set"));
            metadata(id, "delegated499", "delegated499");
            admin(owner, "/" + root + " staff demote " + firm + " " + employee.getUsername());
            BusinessLedger.waitForEmployeeRole(id, employee.getUuid(), "Manager", TIMEOUT);
            rejectBoth(employee, root, firm, id, "delegated499", "delegated499");
            // Server nodes remain granted; the changed firm role is what denies access.
            assertThat(Perms.check(employee.getUsername(), "business.attribute.hq")).contains("has=true");
            assertThat(Perms.check(employee.getUsername(), "business.attribute.discord")).contains("has=true");
            assertThat(BusinessLedger.firmProprietor(id)).hasValue(owner.getUuid());
            assertThat(BusinessLedger.firmDefaultBalance(id).orElseThrow()).isEqualByComparingTo("0");
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
            assertThat(Ledger.personalBalance(employee.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
        }
    }

    private static void promote(MockPlayer owner, MockPlayer employee, String root, String firm, int id, String role) {
        admin(owner, "/" + root + " staff promote " + firm + " " + employee.getUsername());
        BusinessLedger.waitForEmployeeRole(id, employee.getUuid(), role, TIMEOUT);
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
