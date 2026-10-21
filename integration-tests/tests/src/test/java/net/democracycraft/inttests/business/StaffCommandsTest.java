package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Await;
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
 * Multi-player Staff-management coverage: fire/resign/promote/demote/list.
 * Each test seeds a firm + employee via the offer/accept flow before exercising
 * the command under test.
 */
class StaffCommandsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        // Keep firm names distinct from player names generated in the same millisecond.
        return "Firm" + tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    /**
     * Seed: create firm as proprietor, hire `hire`. Returns firm_id.
     * Caller must be inside an Ops.run on `owner` (proprietor) — this method
     * only runs the second Ops.run for `hire` accepting.
     */
    private static int seedFirmWithEmployee(String firm, MockPlayer ownerP, String hireName, MockPlayer hireP) {
        ownerP.chatCommand("/business create " + firm);
        ownerP.awaitMessageContaining("Successfully created", TIMEOUT);
        int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);

        Barrier.settle();
        ownerP.chatCommand("/business offer " + firm + " " + hireName);
        ownerP.awaitMessageContaining("job offer has sent", TIMEOUT);
        return firmId;
    }

    // ---------------------------------------------------------- fire

    @Test
    void fireRemovesEmployee() {
        String owner = Usernames.fresh("FrO");
        String hire = Usernames.fresh("FrH");
        String firm = freshFirmName("Fir");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer h = MockPlayer.connectLocal(hire)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(h.getUuid(), TIMEOUT);

            int[] firmId = new int[1];
            Ops.run(owner, () -> firmId[0] = seedFirmWithEmployee(firm, o, hire, h));

            Ops.run(hire, () -> {
                h.drainMessages();
                h.chatCommand("/business offer accept " + firm);
                h.awaitMessageContaining("accepted a job offer", TIMEOUT);
            });
            BusinessLedger.waitForEmployeeCount(firmId[0], 1, TIMEOUT);

            Ops.run(owner, () -> {
                o.drainMessages();
                o.chatCommand("/business fire " + firm + " " + hire);
                o.awaitMessageContaining("has been fired from " + firm, TIMEOUT);
            });

            BusinessLedger.waitForEmployeeCount(firmId[0], 0, TIMEOUT);
            assertThat(BusinessLedger.isCurrentEmployee(firmId[0], h.getUuid())).isFalse();
        }
    }

    // ---------------------------------------------------------- resign

    @Test
    void resignSelfRemovesFromFirm() {
        String owner = Usernames.fresh("ReO");
        String hire = Usernames.fresh("ReH");
        String firm = freshFirmName("Res");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer h = MockPlayer.connectLocal(hire)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(h.getUuid(), TIMEOUT);

            int[] firmId = new int[1];
            Ops.run(owner, () -> firmId[0] = seedFirmWithEmployee(firm, o, hire, h));

            Ops.run(hire, () -> {
                h.drainMessages();
                h.chatCommand("/business offer accept " + firm);
                h.awaitMessageContaining("accepted a job offer", TIMEOUT);
            });
            BusinessLedger.waitForEmployeeCount(firmId[0], 1, TIMEOUT);

            Ops.run(hire, () -> {
                h.drainMessages();
                h.chatCommand("/business resign " + firm);
                h.awaitMessageContaining("resigned from " + firm, TIMEOUT);
            });

            BusinessLedger.waitForEmployeeCount(firmId[0], 0, TIMEOUT);
        }
    }

    // ---------------------------------------------------------- promote / demote

    @Test
    void promoteAndDemoteCycleRole() {
        String owner = Usernames.fresh("PrO");
        String hire = Usernames.fresh("PrH");
        String firm = freshFirmName("Pro");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer h = MockPlayer.connectLocal(hire)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(h.getUuid(), TIMEOUT);

            int[] firmId = new int[1];
            Ops.run(owner, () -> {
                firmId[0] = seedFirmWithEmployee(firm, o, hire, h);
                // Create a role above the default so promote has somewhere to go.
                Barrier.settle();
                o.chatCommand("/business staff role create " + firm + " Senior 200");
                o.awaitMessageContaining("role Senior has been created", TIMEOUT);
            });

            Ops.run(hire, () -> {
                h.drainMessages();
                h.chatCommand("/business offer accept " + firm);
                h.awaitMessageContaining("accepted a job offer", TIMEOUT);
            });
            BusinessLedger.waitForEmployeeCount(firmId[0], 1, TIMEOUT);
            String startingRole = BusinessLedger.currentEmployeeRoleName(firmId[0], h.getUuid()).orElseThrow();

            Ops.run(owner, () -> {
                Barrier.settle();
                o.drainMessages();
                o.chatCommand("/business staff promote " + firm + " " + hire);
                o.awaitMessageContaining("has been promoted to", TIMEOUT);
            });
            // New role is whatever sits above startingRole — assert it changed.
            String afterPromote = Await.until("the hire's role to change from " + startingRole,
                    () -> BusinessLedger.currentEmployeeRoleName(firmId[0], h.getUuid()).orElse(startingRole),
                    role -> !role.equals(startingRole), TIMEOUT);

            Ops.run(owner, () -> {
                Barrier.settle();
                o.drainMessages();
                o.chatCommand("/business staff demote " + firm + " " + hire);
                o.awaitMessageContaining("has been demoted to", TIMEOUT);
            });
            BusinessLedger.waitForEmployeeRole(firmId[0], h.getUuid(), startingRole, TIMEOUT);
        }
    }

    // ---------------------------------------------------------- list

    @Test
    void listStaffShowsHeader() {
        String owner = Usernames.fresh("LsO");
        String firm = freshFirmName("Lst");

        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.chatCommand("/business staff list " + firm);
                p.awaitMessageContaining("Employees of " + firm, TIMEOUT);
            });
        }
    }
}
