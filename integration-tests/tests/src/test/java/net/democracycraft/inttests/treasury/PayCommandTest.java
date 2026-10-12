package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Treasury's {@code /pay <target> <amount>}. Default permission, no op required.
 *
 * <p>Tests cover: happy path debit/credit + chat receipts, self-pay rejected,
 * insufficient funds rejected.
 */
class PayCommandTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Test
    void payTransfersBetweenTwoPlayers() {
        String alice = Usernames.fresh("PayA");
        String bob = Usernames.fresh("PayB");

        try (MockPlayer a = MockPlayer.connectLocal(alice);
             MockPlayer b = MockPlayer.connectLocal(bob)) {

            BigDecimal aliceStart = Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            BigDecimal bobStart = Ledger.waitForPersonalBalance(b.getUuid(), TIMEOUT);

            a.drainMessages();
            b.drainMessages();
            a.chatCommand("/pay " + bob + " 250");

            // Sender confirmation
            String aReceipt = a.awaitMessageContaining("Paid", TIMEOUT);
            assertThat(aReceipt).contains(bob);

            // Recipient notification
            String bReceipt = b.awaitMessageContaining("Received", TIMEOUT);
            assertThat(bReceipt).contains(alice);

            // DB ground truth
            Ledger.waitForPersonalBalance(a.getUuid(),
                    aliceStart.subtract(new BigDecimal("250.00")), TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(),
                    bobStart.add(new BigDecimal("250.00")), TIMEOUT);
        }
    }

    @Test
    void payRejectsSelfTransfers() {
        String username = Usernames.fresh("PayS");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);

            p.drainMessages();
            p.chatCommand("/pay " + username + " 50");
            String response = p.awaitMessageContaining("cannot pay yourself", TIMEOUT);
            assertThat(response).contains("yourself");
        }
    }

    @Test
    void payRejectsInsufficientFunds() {
        String alice = Usernames.fresh("PiA");
        String bob = Usernames.fresh("PiB");

        try (MockPlayer a = MockPlayer.connectLocal(alice);
             MockPlayer b = MockPlayer.connectLocal(bob)) {

            BigDecimal aliceStart = Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), TIMEOUT);

            a.drainMessages();
            // Default starting balance is $10,000; pay more than that.
            a.chatCommand("/pay " + bob + " 999999");
            a.awaitMessageContaining("Insufficient funds", TIMEOUT);

            // Balance unchanged on the sender side.
            BigDecimal aliceAfter = Ledger.personalBalance(a.getUuid()).orElseThrow();
            assertThat(aliceAfter).isEqualByComparingTo(aliceStart);
        }
    }
}
