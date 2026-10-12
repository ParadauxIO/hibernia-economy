package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Single-player Role-management coverage for /business. */
class RoleCommandsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @Test
    void createRoleAddsRowAndDefaultPermission() {
        String owner = Usernames.fresh("RcO");
        String firm = freshFirmName("Rc");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Ledger.asyncSettle();
                p.chatCommand("/business staff role create " + firm + " Lead 100");
                p.awaitMessageContaining("role Lead has been created", TIMEOUT);

                Ledger.asyncSettle();
                assertThat(BusinessLedger.roleExists(firmId, "Lead")).isTrue();
                assertThat(BusinessLedger.rolePermissionCount(firmId, "Lead")).isEqualTo(1);
            });
        }
    }

    @Test
    void renameRoleChangesName() {
        String owner = Usernames.fresh("RnO");
        String firm = freshFirmName("Rn");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Ledger.asyncSettle();
                p.chatCommand("/business staff role create " + firm + " Junior 50");
                p.awaitMessageContaining("role Junior has been created", TIMEOUT);

                Ledger.asyncSettle();
                p.chatCommand("/business staff role rename " + firm + " Junior Senior");
                p.awaitMessageContaining("renamed to Senior", TIMEOUT);

                Ledger.asyncSettle();
                assertThat(BusinessLedger.roleExists(firmId, "Junior")).isFalse();
                assertThat(BusinessLedger.roleExists(firmId, "Senior")).isTrue();
            });
        }
    }

    @Test
    void deleteRoleRemovesRow() {
        String owner = Usernames.fresh("RdO");
        String firm = freshFirmName("Rd");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Ledger.asyncSettle();
                p.chatCommand("/business staff role create " + firm + " Temp 25");
                p.awaitMessageContaining("role Temp has been created", TIMEOUT);

                Ledger.asyncSettle();
                p.chatCommand("/business staff role delete " + firm + " Temp");
                p.awaitMessageContaining("role Temp has been deleted", TIMEOUT);

                Ledger.asyncSettle();
                assertThat(BusinessLedger.roleExists(firmId, "Temp")).isFalse();
            });
        }
    }

    @Test
    void addAndRemoveRolePermission() {
        String owner = Usernames.fresh("RpO");
        String firm = freshFirmName("Rp");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Ledger.asyncSettle();
                p.chatCommand("/business staff role create " + firm + " Officer 75");
                p.awaitMessageContaining("role Officer has been created", TIMEOUT);
                int baseline = BusinessLedger.rolePermissionCount(firmId, "Officer");

                Ledger.asyncSettle();
                p.chatCommand("/business staff role permission add " + firm + " Officer FINANCIAL");
                p.awaitMessageContaining("permission FINANCIAL has been added", TIMEOUT);

                Ledger.asyncSettle();
                assertThat(BusinessLedger.rolePermissionCount(firmId, "Officer"))
                        .isEqualTo(baseline + 1);

                Ledger.asyncSettle();
                p.chatCommand("/business staff role permission remove " + firm + " Officer FINANCIAL");
                p.awaitMessageContaining("permission FINANCIAL has been removed", TIMEOUT);

                Ledger.asyncSettle();
                assertThat(BusinessLedger.rolePermissionCount(firmId, "Officer"))
                        .isEqualTo(baseline);
            });
        }
    }

    @Test
    void listRolesShowsHeader() {
        String owner = Usernames.fresh("RlO");
        String firm = freshFirmName("Rl");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Ledger.asyncSettle();
                p.chatCommand("/business staff role list " + firm);
                p.awaitMessageContaining("Roles within " + firm, TIMEOUT);
            });
        }
    }

    @Test
    void listRolePermissionsShowsHeader() {
        String owner = Usernames.fresh("RpL");
        String firm = freshFirmName("Pl");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Ledger.asyncSettle();
                p.chatCommand("/business staff role create " + firm + " Hat 10");
                p.awaitMessageContaining("role Hat has been created", TIMEOUT);

                Ledger.asyncSettle();
                p.chatCommand("/business staff role permission list " + firm + " Hat");
                p.awaitMessageContaining("Permissions within Hat", TIMEOUT);
            });
        }
    }
}
