package net.democracycraft.inttests.treasury;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconnectPersistenceTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void reconnectPreservesAccountAndPaymentWithoutRepeatingStartingGrant() {
        String name = Usernames.fresh("Again");
        UUID uuid;
        int account;
        int count;
        BigDecimal expected;
        try (MockPlayer recipient = MockPlayer.connectLocal(Usernames.fresh("Rec"))) {
            BigDecimal recipientStart = Ledger.waitForPersonalBalance(recipient.getUuid(), TIMEOUT);
            try (MockPlayer sender = MockPlayer.connectLocal(name)) {
                // Bypass only GP's login cooldown for this fixture identity.
                Perms.grant(name, "griefprevention.spam");
                uuid = sender.getUuid();
                BigDecimal start = Ledger.waitForPersonalBalance(uuid, TIMEOUT);
                account = Ledger.personalAccountId(uuid).orElseThrow();
                sender.chatCommand("/paya player " + recipient.getUsername() + " 123.45");
                sender.awaitMessageContaining("Paid", TIMEOUT);
                Barrier.settle();
                expected = start.subtract(new BigDecimal("123.45"));
                assertThat(Ledger.personalBalance(uuid).orElseThrow()).isEqualByComparingTo(expected);
                count = Ledger.postingsCountForAccount(account);
            }
            Await.until("server observes disconnect", () -> Perms.check(name, "treasury.pay"),
                    "offline"::equals, TIMEOUT);
            try (MockPlayer reconnected = MockPlayer.connectLocal(name)) {
                Barrier.settle();
                assertThat(reconnected.getUuid()).isEqualTo(uuid);
                assertThat(Ledger.personalAccountId(uuid)).hasValue(account);
                assertThat(Ledger.personalBalance(uuid).orElseThrow()).isEqualByComparingTo(expected);
                assertThat(Ledger.postingsCountForAccount(account)).isEqualTo(count);
                assertThat(Ledger.personalBalance(recipient.getUuid()).orElseThrow())
                        .isEqualByComparingTo(recipientStart.add(new BigDecimal("123.45")));
                reconnected.chatCommand("/balance");
                reconnected.awaitMessageContaining("9,876.55", TIMEOUT);
            }
        }
    }
}
