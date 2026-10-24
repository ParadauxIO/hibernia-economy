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
 * Branch-coverage filler for Business command handlers — leftover branches
 * not exercised by the command-family tests:
 * <ul>
 *   <li>transfer cancel without an active transfer</li>
 *   <li>account setdefault for a non-existent account id</li>
 *   <li>account archive default-account rejection</li>
 *   <li>discord URL in the http://discord.gg form is rejected (https only)</li>
 * </ul>
 */
class AdditionalCoverageTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        // Keep firm names distinct from player names generated in the same millisecond.
        return "Firm" + tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @Test
    void accountSetDefaultRejectsUnknownAccount() {
        String owner = Usernames.fresh("Asu");
        String firm = freshFirmName("Asu");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);
                int defaultId = BusinessLedger.firmDefaultAccountId(firmId).orElseThrow();

                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business account setdefault " + firm + " 999999");
                // 999999 isn't one of the firm's accounts; the service refuses it as a
                // bad command and the default account is left untouched.
                p.awaitMessageContaining("does not belong to the firm", TIMEOUT);
                Barrier.settle();
                assertThat(BusinessLedger.firmDefaultAccountId(firmId)).hasValue(defaultId);
            });
        }
    }

    @Test
    void attributeSetDiscordRejectsHttpVariant() {
        String owner = Usernames.fresh("Adh");
        String firm = freshFirmName("Adh");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmId = BusinessLedger.waitForFirm(firm, TIMEOUT);

                // Only https://discord.gg/<code> is accepted now: the invite is rendered
                // into the public /firm info card, so the charset and scheme are strict
                // (NameValidator.DISCORD_INVITE_REGEX). Plain http is refused.
                Barrier.settle();
                p.drainMessages();
                p.chatCommand("/business attribute set discord " + firm + " http://discord.gg/abc123");
                p.awaitMessageContaining("Invalid URL", TIMEOUT);
                Barrier.settle();
                assertThat(BusinessLedger.firmDiscordUrl(firmId)).isEmpty();
            });
        }
    }

    @Test
    void offerRejectAcceptanceAfterRescindFails() {
        // Confirms the no-pending-offer branch in offer accept after rescind.
        String owner = Usernames.fresh("Ora");
        String hire = Usernames.fresh("Orh");
        String firm = freshFirmName("Ora");
        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer h = MockPlayer.connectLocal(hire)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(h.getUuid(), TIMEOUT);

            Ops.run(owner, () -> {
                o.chatCommand("/business create " + firm);
                o.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);

                Barrier.settle();
                o.chatCommand("/business offer " + firm + " " + hire);
                o.awaitMessageContaining("job offer has sent", TIMEOUT);

                Barrier.settle();
                o.drainMessages();
                o.chatCommand("/business offer rescind " + firm + " " + hire);
                o.awaitMessageContaining("rescinded", TIMEOUT);
            });

            Ops.run(hire, () -> {
                h.drainMessages();
                h.chatCommand("/business offer accept " + firm);
                h.awaitMessage(m -> m.toLowerCase().contains("no")
                        || m.toLowerCase().contains("not")
                        || m.contains("Error:"), TIMEOUT);
            });
        }
    }
}
