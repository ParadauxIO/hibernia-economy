package net.democracycraft.inttests.restapi;

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
 * Direct coverage of the {@code /treasuryapi} command surface — every route on
 * {@link net.democracycraft.treasuryapi.commands.TreasuryAPICommand} (12 routes
 * split into personal + business groups). The REST API behaviour these keys
 * unlock lives in the other {@code restapi/*Test} classes; this class exercises
 * the command-side issue/list/reissue/revoke loops and their rejection
 * branches.
 *
 * <p>Asserts via:
 * <ul>
 *   <li>chat message containing a known fragment from the success/error i18n</li>
 *   <li>{@link ApiTokens} DB helpers ({@code api_keys} row inspection)</li>
 * </ul>
 */
class TreasuryApiCommandTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static String freshFirmName(String tag) {
        return tag + Long.toHexString(Instant.now().toEpochMilli());
    }

    // ============================================================ /treasuryapi (root)

    @Test
    void rootShowsPluginInfo() {
        // treasuryapi.command defaults to `op` — wrap in Ops.run so the
        // brigadier requires-predicate lets the literal through.
        String me = Usernames.fresh("Tri");
        try (MockPlayer p = MockPlayer.connectLocal(me)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(me, () -> {
                p.drainMessages();
                p.chatCommand("/treasuryapi");
                String r = p.awaitMessageContaining("TreasuryAPI", TIMEOUT);
                assertThat(r).contains("API key management");
            });
        }
    }

    // ============================================================ personal

    @Test
    void personalIssueCreatesAndListsKey() {
        String me = Usernames.fresh("Tpi");
        try (MockPlayer p = MockPlayer.connectLocal(me)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(me, p);
            assertThat(key.token()).isNotBlank();
            assertThat(key.revoked()).isFalse();

            // List shows at least one entry referencing the key id.
            Ops.run(me, () -> {
                p.drainMessages();
                p.chatCommand("/treasuryapi personal list");
                p.awaitMessageContaining("Your Personal API Keys", TIMEOUT);
                p.awaitMessageContaining("#" + key.keyId(), TIMEOUT);
            });
        }
    }

    @Test
    void personalListEmptyForFreshPlayer() {
        String me = Usernames.fresh("Tpl");
        try (MockPlayer p = MockPlayer.connectLocal(me)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(me, () -> {
                p.drainMessages();
                p.chatCommand("/treasuryapi personal list");
                p.awaitMessageContaining("have not issued any personal", TIMEOUT);
            });
        }
    }

    @Test
    void personalReissueRotatesToken() {
        String me = Usernames.fresh("Tpr");
        try (MockPlayer p = MockPlayer.connectLocal(me)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            IssuedKey first = ApiTokens.issuePersonal(me, p);

            IssuedKey second = ApiTokens.reissue(me, p, "personal", first.keyId());
            assertThat(second.token())
                    .as("reissue should rotate the JWT")
                    .isNotEqualTo(first.token());
            assertThat(second.jwtId()).isNotEqualTo(first.jwtId());
        }
    }

    @Test
    void personalReissueRejectsUnknownKeyId() {
        String me = Usernames.fresh("Trn");
        try (MockPlayer p = MockPlayer.connectLocal(me)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(me, () -> {
                p.drainMessages();
                p.chatCommand("/treasuryapi personal reissue 99999999");
                p.awaitMessageContaining("not found", TIMEOUT);
            });
        }
    }

    @Test
    void personalReissueRejectsAnotherPlayersKey() {
        String alice = Usernames.fresh("Tra");
        String bob = Usernames.fresh("Trb");
        try (MockPlayer a = MockPlayer.connectLocal(alice);
             MockPlayer b = MockPlayer.connectLocal(bob)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), TIMEOUT);
            IssuedKey alicesKey = ApiTokens.issuePersonal(alice, a);

            Ops.run(bob, () -> {
                b.drainMessages();
                b.chatCommand("/treasuryapi personal reissue " + alicesKey.keyId());
                b.awaitMessageContaining("do not own", TIMEOUT);
            });
        }
    }

    @Test
    void personalRevokeMarksKeyRevoked() {
        String me = Usernames.fresh("Tpv");
        try (MockPlayer p = MockPlayer.connectLocal(me)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(me, p);
            assertThat(key.revoked()).isFalse();

            ApiTokens.revokePersonal(me, p, key.keyId());

            IssuedKey reread = ApiTokens.waitForKeyById(key.keyId(), TIMEOUT);
            assertThat(reread.revoked()).isTrue();
        }
    }

    @Test
    void personalRevokeRejectsUnknownKeyId() {
        String me = Usernames.fresh("Tvn");
        try (MockPlayer p = MockPlayer.connectLocal(me)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(me, () -> {
                p.drainMessages();
                p.chatCommand("/treasuryapi personal revoke 99999999");
                p.awaitMessageContaining("not found", TIMEOUT);
            });
        }
    }

    @Test
    void personalRevokeRejectsAnotherPlayersKey() {
        String alice = Usernames.fresh("Tva");
        String bob = Usernames.fresh("Tvb");
        try (MockPlayer a = MockPlayer.connectLocal(alice);
             MockPlayer b = MockPlayer.connectLocal(bob)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), TIMEOUT);
            IssuedKey alicesKey = ApiTokens.issuePersonal(alice, a);

            Ops.run(bob, () -> {
                b.drainMessages();
                b.chatCommand("/treasuryapi personal revoke " + alicesKey.keyId());
                b.awaitMessageContaining("do not own", TIMEOUT);
            });

            // Alice's key must remain un-revoked.
            IssuedKey reread = ApiTokens.waitForKeyById(alicesKey.keyId(), TIMEOUT);
            assertThat(reread.revoked()).isFalse();
        }
    }

    // ============================================================ business

    @Test
    void businessIssueCreatesAndListsKey() {
        String owner = Usernames.fresh("Tbi");
        String firm = freshFirmName("TbiF");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
            });

            IssuedKey key = ApiTokens.issueBusiness(owner, p, firm);
            assertThat(key.firmId()).isNotNull();

            Ops.run(owner, () -> {
                p.drainMessages();
                p.chatCommand("/treasuryapi business list");
                p.awaitMessageContaining("Your Business API Keys", TIMEOUT);
                p.awaitMessageContaining("#" + key.keyId(), TIMEOUT);
            });
        }
    }

    @Test
    void businessIssueRejectsUnknownFirm() {
        String me = Usernames.fresh("Tbu");
        try (MockPlayer p = MockPlayer.connectLocal(me)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(me, () -> {
                p.drainMessages();
                p.chatCommand("/treasuryapi business issue NoSuchFirmEver");
                p.awaitMessageContaining("Firm not found", TIMEOUT);
            });
        }
    }

    @Test
    void businessIssueRejectsNonProprietor() {
        // Stranger tries to issue a business key for owner's firm.
        String owner = Usernames.fresh("Tbo");
        String stranger = Usernames.fresh("Tbs");
        String firm = freshFirmName("TboF");
        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer s = MockPlayer.connectLocal(stranger)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                o.chatCommand("/business create " + firm);
                o.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
            });

            Ops.run(stranger, () -> {
                s.drainMessages();
                s.chatCommand("/treasuryapi business issue " + firm);
                s.awaitMessageContaining("Only the proprietor", TIMEOUT);
            });
        }
    }

    @Test
    void businessListEmptyForFreshPlayer() {
        String me = Usernames.fresh("Tbe");
        try (MockPlayer p = MockPlayer.connectLocal(me)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(me, () -> {
                p.drainMessages();
                p.chatCommand("/treasuryapi business list");
                p.awaitMessageContaining("have not issued any business", TIMEOUT);
            });
        }
    }

    @Test
    void businessListAccessEmptyForUnemployedPlayer() {
        String me = Usernames.fresh("Tba");
        try (MockPlayer p = MockPlayer.connectLocal(me)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(me, () -> {
                p.drainMessages();
                p.chatCommand("/treasuryapi business list access");
                p.awaitMessageContaining("No business API keys", TIMEOUT);
            });
        }
    }

    @Test
    void businessReissueRotatesToken() {
        String owner = Usernames.fresh("Tbr");
        String firm = freshFirmName("TbrF");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
            });
            IssuedKey first = ApiTokens.issueBusiness(owner, p, firm);

            IssuedKey second = ApiTokens.reissue(owner, p, "business", first.keyId());
            assertThat(second.token()).isNotEqualTo(first.token());
        }
    }

    @Test
    void businessRevokeMarksKeyRevoked() {
        String owner = Usernames.fresh("Tbv");
        String firm = freshFirmName("TbvF");
        try (MockPlayer p = MockPlayer.connectLocal(owner)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                p.chatCommand("/business create " + firm);
                p.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
            });
            IssuedKey key = ApiTokens.issueBusiness(owner, p, firm);

            Ops.run(owner, () -> {
                p.drainMessages();
                p.chatCommand("/treasuryapi business revoke " + key.keyId());
                p.awaitMessageContaining("revoked", TIMEOUT);
            });

            IssuedKey reread = ApiTokens.waitForKeyById(key.keyId(), TIMEOUT);
            assertThat(reread.revoked()).isTrue();
        }
    }

    @Test
    void businessReissueRejectsAnotherPlayersKey() {
        // Owner issues a business key; stranger tries to reissue. Must reject.
        String owner = Usernames.fresh("Tbra");
        String stranger = Usernames.fresh("Tbrb");
        String firm = freshFirmName("TbraF");
        try (MockPlayer o = MockPlayer.connectLocal(owner);
             MockPlayer s = MockPlayer.connectLocal(stranger)) {
            Ledger.waitForPersonalBalance(o.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(s.getUuid(), TIMEOUT);
            Ops.run(owner, () -> {
                o.chatCommand("/business create " + firm);
                o.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firm, TIMEOUT);
            });
            IssuedKey ownersKey = ApiTokens.issueBusiness(owner, o, firm);

            Ops.run(stranger, () -> {
                s.drainMessages();
                s.chatCommand("/treasuryapi business reissue " + ownersKey.keyId());
                // A business key belongs to the firm, not to whoever issued it: only a
                // current proprietor may manage it.
                s.awaitMessageContaining("not a proprietor", TIMEOUT);
            });
        }
    }
}
