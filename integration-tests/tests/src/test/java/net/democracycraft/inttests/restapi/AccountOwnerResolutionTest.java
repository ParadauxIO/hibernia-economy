package net.democracycraft.inttests.restapi;

import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class AccountOwnerResolutionTest {
    @Test
    void namesResolveBackToOriginalAccountAndRejectedChangesPreserveBothOwners() throws Exception {
        try (var owner = MockPlayer.connectLocal(Usernames.fresh("OrOwn"));
             var peer = MockPlayer.connectLocal(Usernames.fresh("OrPe"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), Duration.ofSeconds(10));
            Ledger.waitForPersonalBalance(peer.getUuid(), Duration.ofSeconds(10));
            int account = Ledger.personalAccountId(owner.getUuid()).orElseThrow();
            int other = Ledger.personalAccountId(peer.getUuid()).orElseThrow();
            String service = ApiTokens.mintServiceKey(peer.getUuid()).token();
            String personal = ApiTokens.issuePersonal(owner.getUsername(), owner).token();
            long checkpoint = Ledger.transactionCheckpoint();
            String path = "/admin/accounts/" + account + "/owner";
            // Move away, then resolve the known player's name to reclaim the same ID.
            UUID temporaryOwner = UUID.randomUUID();
            var shared = RestApi.call("PATCH", "/admin/accounts/" + account + "/display-name", service,
                    "{\"displayName\":\"" + owner.getUsername() + "\"}");
            assertThat(shared.status()).as(shared.body()).isEqualTo(200);
            Await.until("player and account share their name", () -> claims(owner.getUsername()), n -> n == 2, Duration.ofSeconds(10));
            // A player's name cannot be transferred to another identity under PAR-501.
            denied(RestApi.call("PATCH", path, service, body(temporaryOwner.toString())), 409, "NAME_RESERVED");
            unchanged(account, owner.getUuid(), other, peer.getUuid(), checkpoint);
            var renamed = RestApi.call("PATCH", "/admin/accounts/" + account + "/display-name", service,
                    "{\"displayName\":\"" + Usernames.fresh("OrAls") + "\"}");
            assertThat(renamed.status()).as(renamed.body()).isEqualTo(200);
            success(RestApi.call("PATCH", path, service, body(temporaryOwner.toString())), account, temporaryOwner);
            state(account, temporaryOwner);
            assertThat(Ledger.personalAccountId(owner.getUuid())).isEmpty();
            success(RestApi.call("PATCH", path, service, body("  " + owner.getUsername() + "  ")), account, owner.getUuid());
            state(account, owner.getUuid());
            // Reassigning to oneself is idempotent through either accepted identifier.
            for (String spec : new String[] {owner.getUsername(), owner.getUuid().toString()}) {
                success(RestApi.call("PATCH", path, service, body(spec)), account, owner.getUuid());
                state(account, owner.getUuid());
            }
            for (String spec : new String[] {peer.getUsername(), peer.getUuid().toString()}) {
                denied(RestApi.call("PATCH", path, service, body(spec)), 409, "PERSONAL_ACCOUNT_EXISTS");
                unchanged(account, owner.getUuid(), other, peer.getUuid(), checkpoint);
            }
            for (String invalid : new String[] {"{}", "{\"owner\":null}", body(""), body("   ")}) {
                denied(RestApi.call("PATCH", path, service, invalid), 400, "INVALID_BODY");
                unchanged(account, owner.getUuid(), other, peer.getUuid(), checkpoint);
            }
            denied(RestApi.call("PATCH", path, service, body(Usernames.fresh("OrNo"))), 404, "PLAYER_NOT_FOUND");
            unchanged(account, owner.getUuid(), other, peer.getUuid(), checkpoint);
            denied(RestApi.call("PATCH", path, personal, body(temporaryOwner.toString())), 401, "TOKEN_REVOKED");
            unchanged(account, owner.getUuid(), other, peer.getUuid(), checkpoint);
            String freshPersonal = ApiTokens.issuePersonal(owner.getUsername(), owner).token();
            denied(RestApi.call("PATCH", path, freshPersonal, body(temporaryOwner.toString())), 403, "FORBIDDEN");
            unchanged(account, owner.getUuid(), other, peer.getUuid(), checkpoint);
            denied(RestApi.call("PATCH", path, null, body(temporaryOwner.toString())), 401, "MISSING_TOKEN");
            unchanged(account, owner.getUuid(), other, peer.getUuid(), checkpoint);
            denied(RestApi.call("PATCH", "/admin/accounts/2000000000/owner", service,
                    body(owner.getUsername())), 404, "ACCOUNT_NOT_FOUND");
            unchanged(account, owner.getUuid(), other, peer.getUuid(), checkpoint);
        }
    }
    private static int claims(String name) {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT claim_count FROM global_name_reservations WHERE name_key=?")) {
            q.setString(1, name);
            try (var r = q.executeQuery()) { return r.next() ? r.getInt(1) : 0; }
        } catch (java.sql.SQLException e) { throw new IllegalStateException("read name claims", e); }
    }
    private static String body(String owner) { return "{\"owner\":\"" + owner + "\"}"; }
    private static void denied(RestApi.Response response, int status, String error) {
        assertThat(response.status()).as("%s", response.body()).isEqualTo(status);
        assertThat(response.error()).isEqualTo(error);
    }
    private static void success(RestApi.Response response, int account, UUID owner) {
        assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
        assertThat(response.json().path("accountId").asInt()).isEqualTo(account);
        assertThat(response.json().path("ownerUuid").asText()).isEqualTo(owner.toString());
        assertThat(response.json().path("accountType").asText()).isEqualTo("PERSONAL");
        assertThat(response.json().path("archived").asBoolean()).isFalse();
    }
    private static void unchanged(int account, UUID owner, int other, UUID peer, long checkpoint) throws Exception {
        state(account, owner);
        state(other, peer);
        assertThat(Ledger.personalAccountId(owner)).hasValue(account);
        assertThat(Ledger.personalAccountId(peer)).hasValue(other);
        assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
    }
    private static void state(int account, UUID owner) throws Exception {
        try (var c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             var q = c.prepareStatement("SELECT BIN_TO_UUID(owner_uuid_bin), account_type, is_archived FROM accounts WHERE account_id = ?")) {
            q.setInt(1, account);
            try (var r = q.executeQuery()) {
                assertThat(r.next()).isTrue();
                assertThat(UUID.fromString(r.getString(1))).isEqualTo(owner);
                assertThat(r.getString(2)).isEqualTo("PERSONAL");
                assertThat(r.getBoolean(3)).isFalse();
            }
        }
        assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("10000");
    }
}
