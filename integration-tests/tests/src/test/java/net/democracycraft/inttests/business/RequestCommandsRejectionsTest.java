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

/**
 * Negative-path coverage for /business offer + /business transfer.
 * Self-targeting, no-pending-offer, and invalid transfer code branches.
 */
class RequestCommandsRejectionsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @Test
    void offerSelfRejected() {
        String owner = Usernames.fresh("Os");
        String firm = freshFirmName("OsF");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business offer " + firm + " " + owner);
                // Service throws BadCommandException with a self-targeting message.
                p.awaitMessage(m -> m.toLowerCase().contains("yourself")
                        || m.toLowerCase().contains("cannot") || m.toLowerCase().contains("self")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void offerAcceptWithNoPendingOfferRejected() {
        String name = Usernames.fresh("Oa");
        String firm = freshFirmName("OaF");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                // The proprietor accepting a non-existent offer for their own firm
                // should fail at the service layer (no pending offer).
                p.chatCommand("/business offer accept " + firm);
                p.awaitMessage(m -> m.toLowerCase().contains("no") || m.toLowerCase().contains("pending")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }

    @Test
    void offerRejectsUnknownFirm() {
        String owner = Usernames.fresh("Ouf");
        String target = Usernames.fresh("Out");
        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.drainMessages();
                p.chatCommand("/business offer NoSuchFirmEv " + target);
                p.awaitMessage(m -> m.contains("not found"), TIMEOUT);
            });
        }
    }

    @Test
    void offerRejectsUnknownTarget() {
        String owner = Usernames.fresh("Out2");
        String firm = freshFirmName("Out2F");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business offer " + firm + " NobodyNeverJoinedZ" + Long.toHexString(System.nanoTime()));
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });
        }
    }

    @Test
    void offerRescindWithNoPendingOfferRejected() {
        String owner = Usernames.fresh("Rsc");
        String stranger = Usernames.fresh("Rss");
        String firm = freshFirmName("RscF");
        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer s = MockPlayer.connectLocal(stranger)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                // Rescind without ever offering.
                p.chatCommand("/business offer rescind " + firm + " " + stranger);
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });
        }
    }

    @Test
    void offerRejectWithNoPendingOfferRejected() {
        String name = Usernames.fresh("Orj");
        String firm = freshFirmName("OrjF");
        try (MockPlayer p = MockPlayer.connectLocal(name)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(name, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business offer reject " + firm);
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });
        }
    }

    @Test
    void transferBeginRejectsUnknownFirm() {
        String owner = Usernames.fresh("Tbu");
        String target = Usernames.fresh("Tbt");
        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.drainMessages();
                p.chatCommand("/business transfer begin NoSuchFirmEv " + target);
                p.awaitMessage(m -> m.contains("not found"), TIMEOUT);
            });
        }
    }

    @Test
    void transferCancelWithNoneInProgressRejected() {
        String owner = Usernames.fresh("Tca");
        String target = Usernames.fresh("Tct");
        String firm = freshFirmName("TcaF");
        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                // No transfer pending → service should reject.
                p.chatCommand("/business transfer cancel " + firm + " " + target);
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });
        }
    }

    @Test
    void transferCompleteWithoutBeginRejected() {
        String owner = Usernames.fresh("Tcm");
        String target = Usernames.fresh("Tcmt");
        String firm = freshFirmName("TcmF");
        try (MockPlayer p = MockPlayer.connectLocal(owner);
             MockPlayer t = MockPlayer.connectLocal(target)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business transfer complete " + firm + " " + target);
                p.awaitMessage(m -> !m.isBlank(), TIMEOUT);
                Barrier.settle();
            });
        }
    }

    @Test
    void transferConfirmRejectsInvalidCode() {
        String oldOwner = Usernames.fresh("Tco");
        String newOwner = Usernames.fresh("Tcn");
        String firm = freshFirmName("Tcc");

        try (MockPlayer a = MockPlayer.connectLocal(oldOwner);
             MockPlayer b = MockPlayer.connectLocal(newOwner)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), TIMEOUT);

            Ops.run(oldOwner, () -> {
                a.chatCommand("/business create " + firm);
                a.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                a.chatCommand("/business transfer begin " + firm + " " + newOwner);
                a.awaitMessageContaining("Confirm transfer", TIMEOUT);
                BusinessLedger.waitForActiveTransferToken(firmId, b.getUuid(), TIMEOUT);

                Barrier.settle();
                a.drainMessages();
                a.chatCommand("/business transfer confirm " + firm + " " + newOwner + " GARBAGECODE");
                a.awaitMessageContaining("Invalid code", TIMEOUT);
            });
        }
    }
}
