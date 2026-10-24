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
 * Multi-player Business request flows: employment offers and proprietorship
 * transfers. Each test runs two mock players concurrently — proprietor /
 * recipient — and asserts via the shared MariaDB.
 */
class RequestCommandsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    // ---------------------------------------------------------- offer + accept

    @Test
    void offerAndAcceptHiresTheTarget() {
        String owner = Usernames.fresh("OoO");
        String hire = Usernames.fresh("OoH");
        String firm = freshFirmName("Off");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer h = MockPlayer.connectLocal(hire)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(h.getUuid(), TIMEOUT);

            int[] firmId = new int[1];
            Ops.run(owner, () -> {
                o.chatCommand("/business create " + firm);
                o.awaitMessageContaining("Successfully created", TIMEOUT);
                firmId[0] = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                o.drainMessages();
                h.drainMessages();
                o.chatCommand("/business offer " + firm + " " + hire);
                // Sender confirmation
                o.awaitMessageContaining("job offer has sent", TIMEOUT);
                // Target gets the offer broadcast
                h.awaitMessageContaining("received a job offer", TIMEOUT);
            });

            // Acceptance has its own perm path; target needs it too
            Ops.run(hire, () -> {
                h.drainMessages();
                h.chatCommand("/business offer accept " + firm);
                h.awaitMessageContaining("accepted a job offer", TIMEOUT);
            });

            BusinessLedger.waitForEmployeeCount(firmId[0], 1, TIMEOUT);
            assertThat(BusinessLedger.isCurrentEmployee(firmId[0], h.getUuid())).isTrue();
        }
    }

    @Test
    void offerAndRejectLeavesNoEmployment() {
        String owner = Usernames.fresh("OrO");
        String hire = Usernames.fresh("OrH");
        String firm = freshFirmName("Rej");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer h = MockPlayer.connectLocal(hire)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(h.getUuid(), TIMEOUT);

            int[] firmId = new int[1];
            Ops.run(owner, () -> {
                o.chatCommand("/business create " + firm);
                o.awaitMessageContaining("Successfully created", TIMEOUT);
                firmId[0] = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                o.chatCommand("/business offer " + firm + " " + hire);
                o.awaitMessageContaining("job offer has sent", TIMEOUT);
            });

            Ops.run(hire, () -> {
                h.drainMessages();
                h.chatCommand("/business offer reject " + firm);
                h.awaitMessageContaining("rejected a job offer", TIMEOUT);
            });

            // No new employee.
            assertThat(BusinessLedger.currentEmployeeCount(firmId[0])).isEqualTo(0);
            assertThat(BusinessLedger.isCurrentEmployee(firmId[0], h.getUuid())).isFalse();
        }
    }

    @Test
    void offerAndRescindRemovesPendingOffer() {
        String owner = Usernames.fresh("OsO");
        String hire = Usernames.fresh("OsH");
        String firm = freshFirmName("Rsc");

        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer h = MockPlayer.connectLocal(hire)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(h.getUuid(), TIMEOUT);

            int[] firmId = new int[1];
            Ops.run(owner, () -> {
                o.chatCommand("/business create " + firm);
                o.awaitMessageContaining("Successfully created", TIMEOUT);
                firmId[0] = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                o.drainMessages();
                h.drainMessages();
                o.chatCommand("/business offer " + firm + " " + hire);
                o.awaitMessageContaining("job offer has sent", TIMEOUT);

                Barrier.settle();
                o.drainMessages();
                o.chatCommand("/business offer rescind " + firm + " " + hire);
                o.awaitMessageContaining("rescinded", TIMEOUT);
            });

            // After rescind, target accepting should fail.
            Ops.run(hire, () -> {
                h.drainMessages();
                h.chatCommand("/business offer accept " + firm);
                // Either an error message or nothing — what we care about is
                // that no employment was recorded.
            });

            assertThat(BusinessLedger.currentEmployeeCount(firmId[0])).isEqualTo(0);
        }
    }

    // ---------------------------------------------------------- transfer flow

    @Test
    void transferCompleteFlowChangesProprietor() {
        String oldOwner = Usernames.fresh("TxO");
        String newOwner = Usernames.fresh("TxN");
        String firm = freshFirmName("Tx");

        try (MockPlayer a = MockPlayer.connectLocal(oldOwner);
             MockPlayer b = MockPlayer.connectLocal(newOwner)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), TIMEOUT);

            int[] firmId = new int[1];
            Ops.run(oldOwner, () -> {
                a.chatCommand("/business create " + firm);
                a.awaitMessageContaining("Successfully created", TIMEOUT);
                firmId[0] = BusinessLedger.waitForFirm(firm, TIMEOUT);
                assertThat(BusinessLedger.firmProprietor(firmId[0])).hasValue(a.getUuid());

                Barrier.settle();
                a.chatCommand("/business transfer begin " + firm + " " + newOwner);
                a.awaitMessageContaining("Confirm transfer", TIMEOUT);
                BusinessLedger.waitForActiveTransferToken(firmId[0], b.getUuid(), TIMEOUT);

                // Pull the code straight from the DB rather than scraping
                // chat — the chat message embeds it inside a click-link
                // component which the plain serialiser collapses.
                String code = BusinessLedger.activeTransferToken(firmId[0], b.getUuid()).orElseThrow();

                Barrier.settle();
                a.drainMessages();
                b.drainMessages();
                a.chatCommand("/business transfer confirm " + firm + " " + newOwner + " " + code);
                a.awaitMessageContaining("request has been confirmed", TIMEOUT);
                b.awaitMessageContaining("offer to become the proprietor", TIMEOUT);
            });

            Ops.run(newOwner, () -> {
                b.drainMessages();
                b.chatCommand("/business transfer complete " + firm + " " + oldOwner);
                b.awaitMessageContaining("Proprietorship of " + firm + " has been transferred", TIMEOUT);
            });

            BusinessLedger.waitForFirmProprietor(firmId[0], b.getUuid(), TIMEOUT);
        }
    }

    @Test
    void transferCancelLeavesProprietorshipUnchanged() {
        String oldOwner = Usernames.fresh("TcO");
        String newOwner = Usernames.fresh("TcN");
        String firm = freshFirmName("Tc");

        try (MockPlayer a = MockPlayer.connectLocal(oldOwner);
             MockPlayer b = MockPlayer.connectLocal(newOwner)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), TIMEOUT);

            int[] firmId = new int[1];
            Ops.run(oldOwner, () -> {
                a.chatCommand("/business create " + firm);
                a.awaitMessageContaining("Successfully created", TIMEOUT);
                firmId[0] = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                a.chatCommand("/business transfer begin " + firm + " " + newOwner);
                a.awaitMessageContaining("Confirm transfer", TIMEOUT);
                BusinessLedger.waitForActiveTransferToken(firmId[0], b.getUuid(), TIMEOUT);

                Barrier.settle();
                a.drainMessages();
                a.chatCommand("/business transfer cancel " + firm + " " + newOwner);
                a.awaitMessageContaining("cancelled", TIMEOUT);
            });

            // Proprietor unchanged.
            assertThat(BusinessLedger.firmProprietor(firmId[0])).hasValue(a.getUuid());
        }
    }

    @Test
    void transferRejectLeavesProprietorshipUnchanged() {
        String oldOwner = Usernames.fresh("TrO");
        String newOwner = Usernames.fresh("TrN");
        String firm = freshFirmName("Tr");

        try (MockPlayer a = MockPlayer.connectLocal(oldOwner);
             MockPlayer b = MockPlayer.connectLocal(newOwner)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), TIMEOUT);

            int[] firmId = new int[1];
            Ops.run(oldOwner, () -> {
                a.chatCommand("/business create " + firm);
                a.awaitMessageContaining("Successfully created", TIMEOUT);
                firmId[0] = BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                a.chatCommand("/business transfer begin " + firm + " " + newOwner);
                a.awaitMessageContaining("Confirm transfer", TIMEOUT);
                BusinessLedger.waitForActiveTransferToken(firmId[0], b.getUuid(), TIMEOUT);

                String code = BusinessLedger.activeTransferToken(firmId[0], b.getUuid()).orElseThrow();
                Barrier.settle();
                a.chatCommand("/business transfer confirm " + firm + " " + newOwner + " " + code);
                a.awaitMessageContaining("request has been confirmed", TIMEOUT);
            });

            Ops.run(newOwner, () -> {
                b.drainMessages();
                b.chatCommand("/business transfer reject " + firm + " " + oldOwner);
                b.awaitMessageContaining("rejected", TIMEOUT);
            });

            assertThat(BusinessLedger.firmProprietor(firmId[0])).hasValue(a.getUuid());
        }
    }
}
