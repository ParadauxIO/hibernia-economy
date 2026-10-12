package net.democracycraft.inttests;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ApiTokens;
import net.democracycraft.inttests.support.ApiTokens.IssuedKey;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-game authority controls. Complements {@link PermissionDenialTest}
 * (which proves the {@code @Permission} annotation gates Brigadier
 * dispatch) by exercising the <em>service-layer</em> ownership and
 * proprietorship checks that fire after the command is dispatchable.
 *
 * <p>Each test ops the actor via {@link Ops#run} so the permission tree
 * isn't the gate — the rejection comes from the plugin's own
 * "you don't own this resource" path. If any of these starts succeeding,
 * something has bypassed the proprietor / owner check.
 */
class PluginAuthorityTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private static String freshFirm(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    @Test
    void nonProprietorCannotDisbandSomeoneElsesFirm() {
        String proprietor = Usernames.fresh("PdP");
        String stranger = Usernames.fresh("PdS");
        String firm = freshFirm("Pdf");

        try (MockPlayer p = MockPlayer.connectLocal(proprietor);
             MockPlayer s = MockPlayer.connectLocal(stranger)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);

            Ops.run(proprietor, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
            });

            // Stranger has business.* via Ops.run, so the command is
            // dispatchable; the rejection must come from the service layer.
            Ops.run(stranger, () -> {
                s.drainMessages();
                s.chatCommand("/business disband " + firm);
                s.awaitMessage(m -> {
                    String lower = m.toLowerCase();
                    return lower.contains("not the proprietor")
                            || lower.contains("not the owner")
                            || lower.contains("only the proprietor")
                            || lower.contains("permission")
                            || lower.contains("error");
                }, TIMEOUT);
            });

            // Firm still exists.
            assertThat(BusinessLedger.firmExists(firm))
                    .as("firm must survive an unauthorised disband attempt")
                    .isTrue();
        }
    }

    @Test
    void nonProprietorCannotIssueBusinessKeyForSomeoneElsesFirm() {
        String proprietor = Usernames.fresh("KbP");
        String stranger = Usernames.fresh("KbS");
        String firm = freshFirm("Kbf");

        try (MockPlayer p = MockPlayer.connectLocal(proprietor);
             MockPlayer s = MockPlayer.connectLocal(stranger)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);

            Ops.run(proprietor, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
            });

            // Stranger has full perms (Ops.run); only the proprietor check
            // should reject the issue.
            Ops.run(stranger, () -> {
                s.drainMessages();
                s.chatCommand("/treasuryapi business issue " + firm);
                s.awaitMessage(m -> {
                    String lower = m.toLowerCase();
                    return lower.contains("proprietor")
                            || lower.contains("not authorised")
                            || lower.contains("not authorized")
                            || lower.contains("permission")
                            || lower.contains("error");
                }, TIMEOUT);
            });

            // No BUSINESS key landed for the stranger.
            IssuedKey shouldBeNull = ApiTokens.latestKeyFor(s.getUuid(), "BUSINESS");
            assertThat(shouldBeNull)
                    .as("stranger must not have a BUSINESS key after the rejected issue")
                    .isNull();
        }
    }

    @Test
    void playerCannotRevokeAnotherPlayersPersonalApiKey() {
        String alice = Usernames.fresh("RvA");
        String mallory = Usernames.fresh("RvM");

        try (MockPlayer a = MockPlayer.connectLocal(alice);
             MockPlayer m = MockPlayer.connectLocal(mallory)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(m.getUuid(), TIMEOUT);

            // Alice issues a PERSONAL key; capture its id.
            IssuedKey aliceKey = ApiTokens.issuePersonal(alice, a);
            assertThat(aliceKey.revoked()).isFalse();

            // Mallory tries to revoke Alice's key by id. Mallory has full
            // permissions via Ops.run; the rejection must come from the
            // owner check inside ApiKeyService.
            Ops.run(mallory, () -> {
                m.drainMessages();
                m.chatCommand("/treasuryapi personal revoke " + aliceKey.keyId());
                m.awaitMessage(msg -> {
                    String lower = msg.toLowerCase();
                    // Plugin returns "You do not own that API key." for the
                    // ownership-mismatch path; "Personal API key not found"
                    // for missing/wrong type.
                    return lower.contains("do not own")
                            || lower.contains("not your")
                            || lower.contains("not the owner")
                            || lower.contains("not authorised")
                            || lower.contains("not authorized")
                            || lower.contains("permission")
                            || lower.contains("not found")
                            || lower.contains("error");
                }, TIMEOUT);
            });

            // Alice's key is still active in the DB.
            IssuedKey afterAttempt = ApiTokens.keyById(aliceKey.keyId());
            assertThat(afterAttempt)
                    .as("Alice's key must still exist after Mallory's failed revoke")
                    .isNotNull();
            assertThat(afterAttempt.revoked())
                    .as("Alice's key must not be marked revoked")
                    .isFalse();
        }
    }
}
