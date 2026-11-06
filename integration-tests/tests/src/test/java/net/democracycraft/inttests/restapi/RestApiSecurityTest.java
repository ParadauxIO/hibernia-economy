package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ApiTokens;
import net.democracycraft.inttests.support.ApiTokens.IssuedKey;
import net.democracycraft.inttests.support.Barrier;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.DevServer;
import net.democracycraft.inttests.support.JwtForge;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.RestApi;
import net.democracycraft.inttests.support.RestApi.Response;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-cutting REST API security regressions. These tests exercise
 * attacks against the API that aren't covered by the happy-path
 * controllers — token tampering, cross-resource reads, cross-firm
 * spending, and the {@code requires_authorization} gate.
 *
 * <p>If any of these starts returning {@code 200} something has gone
 * badly wrong with the auth pipeline.
 */
class RestApiSecurityTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private static String body(Long fromAccountId, long toAccountId, String amount, String memo) {
        StringBuilder sb = new StringBuilder("{");
        if (fromAccountId != null) {
            sb.append("\"fromAccountId\":").append(fromAccountId).append(',');
        }
        sb.append("\"toAccountId\":").append(toAccountId)
          .append(",\"amount\":\"").append(amount).append('"');
        if (memo != null) sb.append(",\"memo\":\"").append(memo).append('"');
        return sb.append('}').toString();
    }

    // ---------------------------------------------------------- token integrity

    @Test
    void tamperedTokenSignatureRejected() {
        String username = Usernames.fresh("RTt");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(username, p);

            // Flip one character of the signature segment (after the second '.').
            String t = key.token();
            int lastDot = t.lastIndexOf('.');
            char origin = t.charAt(lastDot + 1);
            // Pick a different valid base64url char so the segment still decodes.
            char swapped = origin == 'A' ? 'B' : 'A';
            String tampered = t.substring(0, lastDot + 1) + swapped + t.substring(lastDot + 2);
            assertThat(tampered).isNotEqualTo(t);

            Response r = RestApi.postRotate(tampered);
            assertThat(r.status())
                    .as("tampered signature must fail verification, body=%s", r.body())
                    .isEqualTo(401);
            assertThat(r.error()).isEqualTo("INVALID_TOKEN");
        }
    }

    // ---------------------------------------------------------- cross-account read

    @Test
    void crossPlayerAccountReadDenied() {
        String alice = Usernames.fresh("Sa");
        String bob = Usernames.fresh("Sb");
        try (MockPlayer a = MockPlayer.connectLocal(alice);
             MockPlayer b = MockPlayer.connectLocal(bob)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(b.getUuid(), TIMEOUT);

            // Alice issues a token for her own PERSONAL account; Bob has his
            // own account but they have no member relationship.
            IssuedKey aliceKey = ApiTokens.issuePersonal(alice, a);
            int bobAccount = Ledger.personalAccountId(b.getUuid()).orElseThrow();

            Response r = RestApi.getTransactions(aliceKey.token(), bobAccount, null, null);
            assertThat(r.status())
                    .as("Alice must not read Bob's transactions, body=%s", r.body())
                    .isEqualTo(403);
            assertThat(r.error()).isEqualTo("FORBIDDEN");
        }
    }

    // ---------------------------------------------------------- cross-firm spending

    @Test
    void businessTokenCannotTransferFromNonFirmAccount() {
        String proprietorA = Usernames.fresh("FaP");
        String proprietorB = Usernames.fresh("FbP");
        String firmA = "Cfa" + Long.toHexString(Instant.now().toEpochMilli());
        String firmB = "Cfb" + Long.toHexString(Instant.now().toEpochMilli() + 1);

        try (MockPlayer pa = MockPlayer.connectLocal(proprietorA);
             MockPlayer pb = MockPlayer.connectLocal(proprietorB)) {
            Ledger.waitForPersonalBalance(pa.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(pb.getUuid(), TIMEOUT);

            int[] firmBDefaultAcc = new int[1];
            Ops.run(proprietorA, () -> {
                pa.chatCommand("/business create " + firmA);
                pa.awaitMessageContaining("Successfully created", TIMEOUT);
                BusinessLedger.waitForFirm(firmA, TIMEOUT);
            });
            Ops.run(proprietorB, () -> {
                pb.chatCommand("/business create " + firmB);
                pb.awaitMessageContaining("Successfully created", TIMEOUT);
                int firmBId = BusinessLedger.waitForFirm(firmB, TIMEOUT);
                firmBDefaultAcc[0] = BusinessLedger.firmDefaultAccountId(firmBId).orElseThrow();
                Barrier.settle();
                pb.chatCommand("/business deposit " + firmB + " 50");
                pb.awaitMessageContaining("Paid", TIMEOUT);
            });

            // Issue a BUSINESS key for firm A; try to transfer FROM firm B's
            // default account. The API must reject — fromAccountId must
            // belong to the key's firm.
            IssuedKey keyA = ApiTokens.issueBusiness(proprietorA, pa, firmA);
            int recipientAccount = Ledger.personalAccountId(pa.getUuid()).orElseThrow();

            Response r = RestApi.postTransfer(keyA.token(),
                    body((long) firmBDefaultAcc[0], recipientAccount, "10.00", "cross-firm"),
                    null);
            assertThat(r.status())
                    .as("BUSINESS token for firm A must not transfer from firm B's account, body=%s", r.body())
                    .isEqualTo(403);
            assertThat(r.error())
                    .as("error code body=%s", r.body())
                    .isIn("FORBIDDEN", "ACCOUNT_NOT_OWNED_BY_FIRM");
        }
    }

    // ---------------------------------------------------------- requires_authorization

    @Test
    void requiresAuthorizationAccountRejectsRestTransfer() {
        String holder = Usernames.fresh("RaH");
        String recipient = Usernames.fresh("RaR");
        try (MockPlayer h = MockPlayer.connectLocal(holder);
             MockPlayer r = MockPlayer.connectLocal(recipient)) {
            Ledger.waitForPersonalBalance(h.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(r.getUuid(), TIMEOUT);

            IssuedKey key = ApiTokens.issuePersonal(holder, h);
            int recipientAccount = Ledger.personalAccountId(r.getUuid()).orElseThrow();

            // Sanity: a normal transfer succeeds before we flip the flag.
            Response ok = RestApi.postTransfer(key.token(),
                    body(null, recipientAccount, "1.00", "pre"), null);
            assertThat(ok.status()).isEqualTo(200);

            // Flip the requires_authorization flag on the holder's PERSONAL
            // account. The plugin doesn't expose a toggle for this from
            // chat; the REST API spec just says "if either side has the
            // flag, the transfer is rejected with AUTHORIZATION_REQUIRED".
            setRequiresAuthorization(key.accountId(), true);

            Response denied = RestApi.postTransfer(key.token(),
                    body(null, recipientAccount, "1.00", "post"), null);
            assertThat(denied.status())
                    .as("requires_authorization account must not transfer via REST, body=%s",
                            denied.body())
                    .isEqualTo(403);
            assertThat(denied.error()).isEqualTo("AUTHORIZATION_REQUIRED");

            // Tidy up so subsequent tests don't see this account as locked.
            setRequiresAuthorization(key.accountId(), false);
        }
    }

    // ---------------------------------------------------------- forged tokens
    // These tests use a known-secret forge to reach the verifier branches
    // that issued/rotated tokens can't naturally exercise.

    @Test
    void expiredTokenRejected() {
        String username = Usernames.fresh("Ex");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            // Issue a real key so the kid + jti + type all match a DB row;
            // forge a fresh token with the same identity but with exp in the
            // past. Verifier should reject at the exp check, before any DB
            // hit, with TOKEN_EXPIRED.
            IssuedKey key = ApiTokens.issuePersonal(username, p);
            String expiredToken = JwtForge.sign(
                    key.keyId(),
                    key.jwtId(),
                    key.ownerUuid(),
                    "PERSONAL",
                    key.accountId(),
                    Duration.ofSeconds(-60));

            Response r = RestApi.postRotate(expiredToken);
            assertThat(r.status())
                    .as("expired token must be rejected, body=%s", r.body())
                    .isEqualTo(401);
            assertThat(r.error()).isEqualTo("TOKEN_EXPIRED");
        }
    }

    @Test
    void tokenSignedWithWrongSecretRejected() {
        String username = Usernames.fresh("Ws");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(username, p);

            // Forge a structurally-valid token with the right claims, but
            // signed with a DIFFERENT secret. Signature verification fails
            // before any DB hit; verifier returns INVALID_TOKEN.
            Map<String, Object> claims = new LinkedHashMap<>();
            Instant now = Instant.now();
            claims.put("iss", "treasury-api");
            claims.put("sub", key.ownerUuid().toString());
            claims.put("jti", key.jwtId());
            claims.put("type", "PERSONAL");
            claims.put("acc", key.accountId());
            claims.put("iat", now.getEpochSecond());
            claims.put("exp", now.plus(Duration.ofMinutes(5)).getEpochSecond());

            String forged = JwtForge.signCustom(claims, key.keyId(),
                    // ≥32 chars so we never accidentally trigger the new
                    // length validation in JwtConfig before the signing key
                    // even gets used; the secret itself is irrelevant.
                    "wrong-secret-of-sufficient-length-to-not-trip-min-length-check");

            Response r = RestApi.postRotate(forged);
            assertThat(r.status())
                    .as("token signed with wrong secret must be rejected, body=%s", r.body())
                    .isEqualTo(401);
            assertThat(r.error()).isEqualTo("INVALID_TOKEN");
        }
    }

    @Test
    void tokenWithUnknownKeyIdRejected() {
        // Forge a syntactically-valid token whose kid points to a key
        // that does not exist in api_keys. Signature is valid (it's our
        // dev secret); verifier passes parse + exp + kid-parse, then the
        // DB lookup returns null and we get INVALID_TOKEN with "Token
        // key not found".
        long missingKeyId = 9_999_999L;
        String forged = JwtForge.sign(
                missingKeyId,
                UUID.randomUUID().toString(),
                UUID.randomUUID(),
                "PERSONAL",
                1L,
                Duration.ofMinutes(5));

        Response r = RestApi.postRotate(forged);
        assertThat(r.status())
                .as("forged token for unknown keyId must be rejected, body=%s", r.body())
                .isEqualTo(401);
        assertThat(r.error()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void tokenWithTypeClaimMismatchRejected() {
        String username = Usernames.fresh("Tm");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(username, p);

            // Forge a token with the real key's id + jti, but lie about
            // the type — claim BUSINESS for a PERSONAL row. Verifier
            // passes signature + exp + kid + DB lookup + jti freshness,
            // then the type-claim mismatch check fires.
            String forged = JwtForge.sign(
                    key.keyId(),
                    key.jwtId(),
                    key.ownerUuid(),
                    "BUSINESS",
                    /*firmId=*/ 1L,
                    Duration.ofMinutes(5));

            Response r = RestApi.postRotate(forged);
            assertThat(r.status())
                    .as("type-claim mismatch must be rejected, body=%s", r.body())
                    .isEqualTo(401);
            assertThat(r.error()).isEqualTo("INVALID_TOKEN");
        }
    }

    // ---------------------------------------------------------- helpers

    private static void setRequiresAuthorization(int accountId, boolean value) {
        try (Connection c = DriverManager.getConnection(
                DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE accounts SET requires_authorization = ? WHERE account_id = ?")) {
            ps.setBoolean(1, value);
            ps.setInt(2, accountId);
            int updated = ps.executeUpdate();
            if (updated != 1) {
                throw new AssertionError(
                        "Expected to update 1 accounts row, updated " + updated);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to set requires_authorization", e);
        }
    }
}
