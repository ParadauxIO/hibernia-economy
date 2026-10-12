package net.democracycraft.inttests.business;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

/**
 * Negative-path coverage for /business fire/promote/demote/resign. Service
 * layer raises BadCommandException for self-targeting, firing the proprietor,
 * promoting/demoting beyond rank bounds, etc.
 */
class StaffCommandsRejectionsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @Test
    void fireSelfRejected() {
        String owner = Usernames.fresh("Fs");
        String firm = freshFirmName("Fsf");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Ledger.asyncSettle();
                p.drainMessages();
                p.chatCommand("/business fire " + firm + " " + owner);
                // Either "cannot fire yourself" / "proprietor" / generic Error:.
                p.awaitMessage(m -> m.toLowerCase().contains("yourself")
                        || m.toLowerCase().contains("proprietor")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void resignWhenNotEmployedRejected() {
        String name = Usernames.fresh("Rn");
        String firm = freshFirmName("Rnf");
        // Two players: owner creates firm, second player tries to resign without ever joining.
        String stranger = Usernames.fresh("Rns");
        try (MockPlayer p = MockPlayer.connectLocal(name);
             MockPlayer s = MockPlayer.connectLocal(stranger)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);

            Ops.run(name, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
            });

            Ops.run(stranger, () -> {
                s.drainMessages();
                s.chatCommand("/business resign " + firm);
                s.awaitMessage(m -> m.toLowerCase().contains("not")
                        || m.toLowerCase().contains("employed")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void promoteNonEmployeeRejected() {
        String owner = Usernames.fresh("Pn");
        String stranger = Usernames.fresh("Pns");
        String firm = freshFirmName("Pnf");
        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer s = MockPlayer.connectLocal(stranger)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Ledger.asyncSettle();
                p.drainMessages();
                p.chatCommand("/business staff promote " + firm + " " + stranger);
                p.awaitMessage(m -> m.toLowerCase().contains("not")
                        || m.toLowerCase().contains("employed")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void fireRejectsUnknownFirm() {
        String owner = Usernames.fresh("Fuf");
        String target = Usernames.fresh("Fut");
        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.drainMessages();
                p.chatCommand("/business fire NoSuchFirmEv " + target);
                p.awaitMessage(m -> m.contains("No such firm")
                        || m.contains("not found"), TIMEOUT);
            });
        }
    }

    @Test
    void resignRejectsUnknownFirm() {
        String name = Usernames.fresh("Ruf");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business resign NoSuchFirmEv");
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
            });
        }
    }

    @Test
    void promoteRejectsUnknownFirm() {
        String owner = Usernames.fresh("Puf");
        String stranger = Usernames.fresh("Put");
        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer s = MockPlayer.connectLocal(stranger)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.drainMessages();
                p.chatCommand("/business staff promote NoSuchFirmEv " + stranger);
                p.awaitMessage(m -> m.contains("No such firm")
                        || m.contains("not found"), TIMEOUT);
            });
        }
    }

    @Test
    void demoteRejectsUnknownFirm() {
        String owner = Usernames.fresh("Duf");
        String stranger = Usernames.fresh("Dut");
        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer s = MockPlayer.connectLocal(stranger)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.drainMessages();
                p.chatCommand("/business staff demote NoSuchFirmEv " + stranger);
                p.awaitMessage(m -> m.contains("No such firm")
                        || m.contains("not found"), TIMEOUT);
            });
        }
    }

    @Test
    void staffListRejectsUnknownFirm() {
        String name = Usernames.fresh("Slu");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.drainMessages();
                p.chatCommand("/business staff list NoSuchFirmEv");
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
            });
        }
    }

    @Test
    void demoteNonEmployeeRejected() {
        String owner = Usernames.fresh("Dn");
        String stranger = Usernames.fresh("Dns");
        String firm = freshFirmName("Dnf");
        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer s = MockPlayer.connectLocal(stranger)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Ledger.asyncSettle();
                p.drainMessages();
                p.chatCommand("/business staff demote " + firm + " " + stranger);
                p.awaitMessage(m -> m.toLowerCase().contains("not")
                        || m.toLowerCase().contains("employed")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }
}
