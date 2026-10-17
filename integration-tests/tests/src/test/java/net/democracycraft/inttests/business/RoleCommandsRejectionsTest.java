package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Negative-path coverage for /business staff role commands. Validates that
 * duplicate names, rank collisions, and operations on default/proprietor roles
 * surface a player-visible error.
 */
class RoleCommandsRejectionsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @Test
    void createDuplicateRoleNameRejected() {
        String owner = Usernames.fresh("Rd");
        String firm = freshFirmName("Rdf");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                // "Manager" is one of the default roles seeded at firm creation.
                p.chatCommand("/business staff role create " + firm + " Manager 100");
                p.awaitMessage(m -> m.contains("already used") || m.contains("Error:")
                        || m.toLowerCase().contains("conflict"), TIMEOUT);

                // Default roles still in DB; nothing extra inserted.
                assertThat(BusinessLedger.roleExists(firmId, "Manager")).isTrue();
            });
        }
    }

    @Test
    void deleteProprietorRoleRejected() {
        String owner = Usernames.fresh("Pd");
        String firm = freshFirmName("Pdf");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business staff role delete " + firm + " Proprietor");
                p.awaitMessage(m -> m.toLowerCase().contains("proprietor")
                        || m.contains("Error:"), TIMEOUT);

                assertThat(BusinessLedger.roleExists(firmId, "Proprietor")).isTrue();
            });
        }
    }

    @Test
    void renameRoleToExistingNameRejected() {
        String owner = Usernames.fresh("Rn");
        String firm = freshFirmName("Rnf");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                // Rename Manager -> Supervisor (both default-seeded).
                p.chatCommand("/business staff role rename " + firm + " Manager Supervisor");
                p.awaitMessage(m -> m.contains("already") || m.contains("Error:")
                        || m.toLowerCase().contains("exists"), TIMEOUT);
            });
        }
    }

    @Test
    void createRejectsUnknownFirm() {
        String name = Usernames.fresh("Crf");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business staff role create NoSuchFirmEv Lead 100");
                p.awaitMessage(m -> m.contains("not found")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void renameRejectsUnknownFirm() {
        String name = Usernames.fresh("Rnu");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business staff role rename NoSuchFirmEv Old New");
                p.awaitMessage(m -> m.contains("not found")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void deleteRejectsUnknownFirm() {
        String name = Usernames.fresh("Dlu");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business staff role delete NoSuchFirmEv Lead");
                p.awaitMessage(m -> m.contains("not found")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void permissionAddRejectsUnknownFirm() {
        String name = Usernames.fresh("Pau");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business staff role permission add NoSuchFirmEv Lead FINANCIAL");
                p.awaitMessage(m -> m.contains("not found")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void permissionAddRejectsUnknownRole() {
        String owner = Usernames.fresh("Pur");
        String firm = freshFirmName("PurF");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business staff role permission add " + firm + " NoSuchRole FINANCIAL");
                p.awaitMessage(m -> m.contains("not found")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void permissionAddRejectsInvalidPermissionEnum() {
        // The <permission> arg is parsed by RolePermission.fromString(...).
        // An invalid value should throw, surfacing as the generic "Error: …"
        // path the framework uses for IllegalArgumentException.
        String owner = Usernames.fresh("Pae");
        String firm = freshFirmName("PaeF");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                Barrier.settle();

                p.chatCommand("/business staff role create " + firm + " Worker 200");
                p.awaitMessageContaining("Worker has been created", TIMEOUT);
                Barrier.settle();

                p.drainMessages();
                p.chatCommand("/business staff role permission add " + firm + " Worker NOTAREALPERM");
                p.awaitMessage(m -> m.contains("Error:")
                        || m.toLowerCase().contains("invalid")
                        || m.toLowerCase().contains("unknown"), TIMEOUT);
            });
        }
    }

    @Test
    void permissionRemoveRejectsUnknownFirm() {
        String name = Usernames.fresh("Pru");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business staff role permission remove NoSuchFirmEv Lead FINANCIAL");
                p.awaitMessage(m -> m.contains("not found")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void permissionRemoveRejectsPermissionNotPresent() {
        String owner = Usernames.fresh("Prp");
        String firm = freshFirmName("PrpF");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
                Barrier.settle();

                p.chatCommand("/business staff role create " + firm + " Worker 200");
                p.awaitMessageContaining("Worker has been created", TIMEOUT);
                Barrier.settle();

                p.drainMessages();
                // Worker was just created with DEFAULT only; FINANCIAL not present.
                p.chatCommand("/business staff role permission remove " + firm + " Worker FINANCIAL");
                p.awaitMessage(m -> m.contains("not found")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void listRolesRejectsUnknownFirm() {
        String name = Usernames.fresh("Lru");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business staff role list NoSuchFirmEv");
                p.awaitMessage(m -> m.contains("not found")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void listRolePermissionsRejectsUnknownFirm() {
        String name = Usernames.fresh("Lpu");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business staff role permission list NoSuchFirmEv Lead");
                p.awaitMessage(m -> m.contains("not found")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void deleteUnknownRoleRejected() {
        String owner = Usernames.fresh("Du");
        String firm = freshFirmName("Duf");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business staff role delete " + firm + " NoSuchRole");
                p.awaitMessage(m -> m.toLowerCase().contains("not found")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }
}
