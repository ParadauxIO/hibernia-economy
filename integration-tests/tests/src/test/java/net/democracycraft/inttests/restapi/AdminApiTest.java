package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ApiTokens;
import net.democracycraft.inttests.support.ApiTokens.IssuedKey;
import net.democracycraft.inttests.support.BusinessLedger;
import net.democracycraft.inttests.support.DevServer;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.Ops;
import net.democracycraft.inttests.support.RestApi;
import net.democracycraft.inttests.support.RestApi.Response;
import net.democracycraft.inttests.support.Usernames;
import net.democracycraft.inttests.support.WebhookReceiver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live coverage of the SERVICE-scoped admin API, {@code /api/v1/admin/**} — the endpoints
 * the economy-explorer admin tool drives with its SERVICE key.
 *
 * <p>Every endpoint is checked for the two gates in front of it — a token is required
 * (401), and it must be a SERVICE key (403 for a real, in-game-issued PERSONAL key) — and
 * then for a happy path and a domain-level denial, asserting the database as well as the
 * response. Nothing in-game can issue a SERVICE key, so the suite mints one the way
 * {@code economy-explorer/scripts/mint-service-token.mjs} does
 * ({@link ApiTokens#mintServiceKey}).
 *
 * <p>The REST API commits before it answers, so responses are final: no barriers needed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminApiTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final String SECRET_HEX = "a".repeat(64);
    private static final UUID SOME_OWNER = UUID.fromString("00000000-0000-4000-8000-00000000a0a0");

    private String service;
    private String personal;

    @BeforeAll
    void keys() {
        service = ApiTokens.mintServiceKey(UUID.randomUUID()).token();
        String holder = Usernames.fresh("AdP");
        try (MockPlayer p = MockPlayer.connectLocal(holder)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            personal = ApiTokens.issuePersonal(holder, p).token();
        }
    }

    // ------------------------------------------------------------------ the two gates

    /** Every admin route, with a well-formed body so only the auth gates can refuse it. */
    static Stream<Arguments> adminEndpoints() {
        String hook = WebhookReceiver.get().url("admin-gate");
        return Stream.of(
                Arguments.of("PATCH", "/admin/accounts/1/display-name", "{\"displayName\":\"Gate\"}"),
                Arguments.of("PATCH", "/admin/accounts/1/owner", "{\"owner\":\"" + SOME_OWNER + "\"}"),
                Arguments.of("POST", "/admin/accounts/1/archive", null),
                Arguments.of("POST", "/admin/accounts/1/unarchive", null),
                Arguments.of("POST", "/admin/api-keys/1/revoke", null),
                Arguments.of("POST", "/admin/api-keys/1/rotate", null),
                Arguments.of("POST", "/admin/firms/1/disband", null),
                Arguments.of("POST", "/admin/firms/1/rename", "{\"newName\":\"Gate Firm\"}"),
                Arguments.of("PATCH", "/admin/firms/1", "{\"hqRegion\":\"gate\"}"),
                Arguments.of("PUT", "/admin/rate-limit-overrides/" + SOME_OWNER, "{\"multiplier\":2}"),
                Arguments.of("DELETE", "/admin/rate-limit-overrides/" + SOME_OWNER, null),
                Arguments.of("POST", "/admin/transfers",
                        "{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":\"1.00\"}"),
                Arguments.of("POST", "/admin/webhooks", webhookBody(SOME_OWNER, 1, hook)),
                Arguments.of("PATCH", "/admin/webhooks/1/active", "{\"active\":false}"),
                Arguments.of("PATCH", "/admin/webhooks/1/url", "{\"targetUrl\":\"" + hook + "\"}"),
                Arguments.of("PATCH", "/admin/webhooks/1/secret", "{\"secret\":\"" + SECRET_HEX + "\"}"),
                Arguments.of("DELETE", "/admin/webhooks/1", null));
    }

    @ParameterizedTest(name = "{0} {1} without a token → 401")
    @MethodSource("adminEndpoints")
    void everyAdminEndpointRequiresAToken(String method, String path, String body) {
        Response r = RestApi.call(method, path, null, body);

        assertThat(r.status()).as("%s %s: %s", method, path, r.body()).isEqualTo(401);
        assertThat(r.error()).isEqualTo("MISSING_TOKEN");
    }

    @ParameterizedTest(name = "{0} {1} with a PERSONAL key → 403")
    @MethodSource("adminEndpoints")
    void everyAdminEndpointRefusesNonServiceKeys(String method, String path, String body) {
        Response r = RestApi.call(method, path, personal, body);

        assertThat(r.status()).as("%s %s: %s", method, path, r.body()).isEqualTo(403);
        assertThat(r.error()).isEqualTo("FORBIDDEN");
    }

    // ------------------------------------------------------------------ accounts

    @Test
    void accountRenameArchiveAndUnarchive() {
        int accountId = governmentAccount("AdmAcc");

        Response renamed = RestApi.call("PATCH", "/admin/accounts/" + accountId + "/display-name", service,
                "{\"displayName\":\"  Renamed By Admin  \"}");
        assertThat(renamed.status()).as(renamed.body()).isEqualTo(200);
        assertThat(renamed.json().path("displayName").asText()).isEqualTo("Renamed By Admin");
        assertThat(accountColumn(accountId, "display_name")).contains("Renamed By Admin");

        Response archived = RestApi.call("POST", "/admin/accounts/" + accountId + "/archive", service, null);
        assertThat(archived.status()).as(archived.body()).isEqualTo(200);
        assertThat(archived.json().path("archived").asBoolean()).isTrue();
        assertThat(accountColumn(accountId, "is_archived")).contains("1");

        Response restored = RestApi.call("POST", "/admin/accounts/" + accountId + "/unarchive", service, null);
        assertThat(restored.status()).as(restored.body()).isEqualTo(200);
        assertThat(restored.json().path("archived").asBoolean()).isFalse();
        assertThat(accountColumn(accountId, "is_archived")).contains("0");
    }

    @Test
    void accountRenameRejectsAnOverlongNameAndUnknownAccountsAre404() {
        int accountId = governmentAccount("AdmLng");
        String before = accountColumn(accountId, "display_name").orElseThrow();

        Response tooLong = RestApi.call("PATCH", "/admin/accounts/" + accountId + "/display-name", service,
                "{\"displayName\":\"" + "x".repeat(256) + "\"}");
        assertThat(tooLong.status()).as(tooLong.body()).isEqualTo(400);
        assertThat(tooLong.error()).isEqualTo("INVALID_BODY");
        assertThat(accountColumn(accountId, "display_name")).contains(before);

        Response missing = RestApi.call("POST", "/admin/accounts/2000000000/archive", service, null);
        assertThat(missing.status()).as(missing.body()).isEqualTo(404);
        assertThat(missing.error()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void accountOwnerChangeMovesOwnershipButNeverGivesAPlayerASecondPersonalAccount() {
        int govAccount = governmentAccount("AdmOwn");
        UUID newOwner = UUID.randomUUID();

        Response moved = RestApi.call("PATCH", "/admin/accounts/" + govAccount + "/owner", service,
                "{\"owner\":\"" + newOwner + "\"}");
        assertThat(moved.status()).as(moved.body()).isEqualTo(200);
        assertThat(moved.json().path("ownerUuid").asText()).isEqualTo(newOwner.toString());
        assertThat(accountOwner(govAccount)).contains(newOwner);

        String a = Usernames.fresh("AdOa");
        String b = Usernames.fresh("AdOb");
        try (MockPlayer pa = MockPlayer.connectLocal(a);
             MockPlayer pb = MockPlayer.connectLocal(b)) {
            Ledger.waitForPersonalBalance(pa.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(pb.getUuid(), TIMEOUT);
            int aPersonal = Ledger.personalAccountId(pa.getUuid()).orElseThrow();

            Response denied = RestApi.call("PATCH", "/admin/accounts/" + aPersonal + "/owner", service,
                    "{\"owner\":\"" + pb.getUuid() + "\"}");

            assertThat(denied.status()).as(denied.body()).isEqualTo(409);
            assertThat(denied.error()).isEqualTo("PERSONAL_ACCOUNT_EXISTS");
            assertThat(accountOwner(aPersonal)).contains(pa.getUuid());
        }
    }

    // ------------------------------------------------------------------ api keys

    @Test
    void apiKeyRevokeKillsTheTokenAndUnknownKeysAre404() {
        String holder = Usernames.fresh("AdK");
        try (MockPlayer p = MockPlayer.connectLocal(holder)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(holder, p);

            Response revoked = RestApi.call("POST", "/admin/api-keys/" + key.keyId() + "/revoke", service, null);
            assertThat(revoked.status()).as(revoked.body()).isEqualTo(204);
            assertThat(ApiTokens.keyById(key.keyId()).revoked()).isTrue();

            Response use = RestApi.postRotate(key.token());
            assertThat(use.status()).isEqualTo(401);
            assertThat(use.error()).isEqualTo("TOKEN_REVOKED");
        }

        Response missing = RestApi.call("POST", "/admin/api-keys/2000000000/revoke", service, null);
        assertThat(missing.status()).as(missing.body()).isEqualTo(404);
        assertThat(missing.error()).isEqualTo("KEY_NOT_FOUND");
    }

    @Test
    void apiKeyRotateSupersedesTheTokenButARevokedKeyCannotBeRotated() {
        String holder = Usernames.fresh("AdR");
        try (MockPlayer p = MockPlayer.connectLocal(holder)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(holder, p);

            Response rotated = RestApi.call("POST", "/admin/api-keys/" + key.keyId() + "/rotate", service, null);
            assertThat(rotated.status()).as(rotated.body()).isEqualTo(200);
            assertThat(Instant.parse(rotated.json().path("expiresAt").asText())).isAfter(Instant.now());
            assertThat(ApiTokens.keyById(key.keyId()).jwtId()).isNotEqualTo(key.jwtId());

            Response use = RestApi.postRotate(key.token());
            assertThat(use.status()).isEqualTo(401);
            assertThat(use.error()).isEqualTo("TOKEN_SUPERSEDED");

            ApiTokens.revokeInDb(key.keyId());
            Response refused = RestApi.call("POST", "/admin/api-keys/" + key.keyId() + "/rotate", service, null);
            assertThat(refused.status()).as(refused.body()).isEqualTo(409);
            assertThat(refused.error()).isEqualTo("TOKEN_REVOKED");
        }
    }

    // ------------------------------------------------------------------ rate-limit overrides

    @Test
    void rateLimitOverrideSetAndClearButANonPositiveMultiplierIsRejected() {
        UUID owner = UUID.randomUUID();
        String path = "/admin/rate-limit-overrides/" + owner;

        Response zero = RestApi.call("PUT", path, service, "{\"multiplier\":0}");
        assertThat(zero.status()).as(zero.body()).isEqualTo(400);
        assertThat(zero.error()).isEqualTo("INVALID_BODY");
        assertThat(rateLimitMultiplier(owner)).isEmpty();

        Response set = RestApi.call("PUT", path, service, "{\"multiplier\":2.5,\"note\":\"integration test\"}");
        assertThat(set.status()).as(set.body()).isEqualTo(204);
        assertThat(rateLimitMultiplier(owner)).hasValueSatisfying(m -> assertThat(m).isEqualByComparingTo("2.50"));

        Response cleared = RestApi.call("DELETE", path, service, null);
        assertThat(cleared.status()).as(cleared.body()).isEqualTo(204);
        assertThat(rateLimitMultiplier(owner)).isEmpty();
    }

    // ------------------------------------------------------------------ transfers

    @Test
    void adminTransferMovesMoneyBetweenAnyAccountsButNotMoreThanTheSourceHolds() {
        String from = Usernames.fresh("AdTf");
        String to = Usernames.fresh("AdTt");
        try (MockPlayer f = MockPlayer.connectLocal(from);
             MockPlayer t = MockPlayer.connectLocal(to)) {
            BigDecimal fStart = Ledger.waitForPersonalBalance(f.getUuid(), TIMEOUT);
            BigDecimal tStart = Ledger.waitForPersonalBalance(t.getUuid(), TIMEOUT);
            int fAcct = Ledger.personalAccountId(f.getUuid()).orElseThrow();
            int tAcct = Ledger.personalAccountId(t.getUuid()).orElseThrow();

            Response ok = RestApi.call("POST", "/admin/transfers", service,
                    transferBody(fAcct, tAcct, "25.00", "admin correction"));
            assertThat(ok.status()).as(ok.body()).isEqualTo(200);
            assertThat(ok.json().path("txnId").asLong()).isPositive();
            assertThat(ok.json().path("amount").asText()).isEqualTo("25.00");
            assertThat(Ledger.personalBalance(f.getUuid()).orElseThrow())
                    .isEqualByComparingTo(fStart.subtract(new BigDecimal("25.00")));
            assertThat(Ledger.personalBalance(t.getUuid()).orElseThrow())
                    .isEqualByComparingTo(tStart.add(new BigDecimal("25.00")));

            Response broke = RestApi.call("POST", "/admin/transfers", service,
                    transferBody(fAcct, tAcct, "999999.00", "too much"));
            assertThat(broke.status()).as(broke.body()).isEqualTo(422);
            assertThat(broke.error()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(Ledger.personalBalance(f.getUuid()).orElseThrow())
                    .isEqualByComparingTo(fStart.subtract(new BigDecimal("25.00")));
        }
    }

    // ------------------------------------------------------------------ webhooks

    @Test
    void webhookLifecycleIsOwnerScoped() {
        String holder = Usernames.fresh("AdW");
        try (MockPlayer p = MockPlayer.connectLocal(holder)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            UUID owner = p.getUuid();
            int account = Ledger.personalAccountId(owner).orElseThrow();
            WebhookReceiver receiver = WebhookReceiver.get();

            Response created = RestApi.call("POST", "/admin/webhooks", service,
                    webhookBody(owner, account, receiver.url("admin-a")));
            assertThat(created.status()).as(created.body()).isEqualTo(200);
            long id = created.json().path("subscriptionId").asLong();
            assertThat(webhookColumn(id, "target_url")).contains(receiver.url("admin-a"));
            String scoped = "/admin/webhooks/" + id;

            // Scoped to someone else's ownerUuid, nothing matches.
            Response foreign = RestApi.call("PATCH", scoped + "/active?ownerUuid=" + SOME_OWNER, service,
                    "{\"active\":false}");
            assertThat(foreign.status()).as(foreign.body()).isEqualTo(200);
            assertThat(foreign.json().path("affected").asInt()).isZero();
            assertThat(webhookColumn(id, "active")).contains("1");

            assertAffected(RestApi.call("PATCH", scoped + "/active?ownerUuid=" + owner, service,
                    "{\"active\":false}"));
            assertThat(webhookColumn(id, "active")).contains("0");

            assertAffected(RestApi.call("PATCH", scoped + "/url", service,
                    "{\"targetUrl\":\"" + receiver.url("admin-b") + "\"}"));
            assertThat(webhookColumn(id, "target_url")).contains(receiver.url("admin-b"));

            assertAffected(RestApi.call("PATCH", scoped + "/secret", service,
                    "{\"secret\":\"" + "b".repeat(64) + "\"}"));
            assertThat(webhookColumn(id, "secret")).contains("b".repeat(64));

            assertAffected(RestApi.call("DELETE", scoped, service, null));
            assertThat(webhookColumn(id, "subscription_id")).isEmpty();
        }
    }

    @Test
    void webhookCreateRejectsNonPublicTargetsAndUnknownKeyTypes() {
        Response privateTarget = RestApi.call("POST", "/admin/webhooks", service,
                webhookBody(SOME_OWNER, 1, "http://10.0.0.1/hook"));
        assertThat(privateTarget.status()).as(privateTarget.body()).isEqualTo(400);
        assertThat(privateTarget.error()).isEqualTo("INVALID_WEBHOOK_URL");

        Response badType = RestApi.call("POST", "/admin/webhooks", service,
                webhookBody(SOME_OWNER, 1, WebhookReceiver.get().url("admin-c")).replace("PERSONAL", "SERVICE"));
        assertThat(badType.status()).as(badType.body()).isEqualTo(400);
        assertThat(badType.error()).isEqualTo("INVALID_BODY");

        assertThat(webhookCountFor(SOME_OWNER)).isZero();
    }

    // ------------------------------------------------------------------ firms

    @Test
    void firmRenameUpdateAndDisband() {
        String proprietor = Usernames.fresh("AdF");
        String firm = "AdmF" + Long.toHexString(Instant.now().toEpochMilli());
        String renamed = "AdmR" + Long.toHexString(Instant.now().toEpochMilli());
        try (MockPlayer p = MockPlayer.connectLocal(proprietor)) {
            BigDecimal start = Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            int firmId = fundedFirm(p, proprietor, firm, "300");
            Ledger.waitForPersonalBalance(p.getUuid(), start.subtract(new BigDecimal("300")), TIMEOUT);

            Response r = RestApi.call("POST", "/admin/firms/" + firmId + "/rename", service,
                    "{\"newName\":\"" + renamed + "\"}");
            assertThat(r.status()).as(r.body()).isEqualTo(200);
            assertThat(r.json().path("displayName").asText()).isEqualTo(renamed);
            assertThat(BusinessLedger.firmId(renamed)).contains(firmId);

            Response details = RestApi.call("PATCH", "/admin/firms/" + firmId, service, "{\"hqRegion\":\"spawn-hq\"}");
            assertThat(details.status()).as(details.body()).isEqualTo(200);
            assertThat(BusinessLedger.firmHqRegion(firmId)).contains("spawn-hq");

            Response disband = RestApi.call("POST", "/admin/firms/" + firmId + "/disband", service, null);
            assertThat(disband.status()).as(disband.body()).isEqualTo(200);
            JsonNode d = disband.json();
            assertThat(d.path("archived").asBoolean()).isTrue();
            assertThat(BusinessLedger.firmIsArchived(renamed)).isTrue();
            // The default account's 300 is swept back to the proprietor's personal account.
            assertThat(Ledger.personalBalance(p.getUuid()).orElseThrow()).isEqualByComparingTo(start);
        }
    }

    @Test
    void firmRenameRejectsControlCharacters() {
        // The non-chat vector for the names FirmNameAttacksTest can't send through chat.
        String proprietor = Usernames.fresh("AdN");
        String firm = "AdmN" + Long.toHexString(Instant.now().toEpochMilli());
        try (MockPlayer p = MockPlayer.connectLocal(proprietor)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            int firmId = fundedFirm(p, proprietor, firm, null);

            for (String bad : new String[] {"Acme\\nEvil", "Acme\\tTab", "Acme\\u0007Bell"}) {
                Response r = RestApi.call("POST", "/admin/firms/" + firmId + "/rename", service,
                        "{\"newName\":\"" + bad + "\"}");
                assertThat(r.status()).as("rename to %s: %s", bad, r.body()).isEqualTo(400);
                assertThat(r.error()).isEqualTo("INVALID_BODY");
            }
            assertThat(BusinessLedger.firmId(firm)).contains(firmId);
        }
    }

    @Test
    void firmEndpointsRefuseUnknownFirmsAndEmptyUpdates() {
        Response disband = RestApi.call("POST", "/admin/firms/2000000000/disband", service, null);
        assertThat(disband.status()).as(disband.body()).isEqualTo(404);
        assertThat(disband.error()).isEqualTo("FIRM_NOT_FOUND");

        Response empty = RestApi.call("PATCH", "/admin/firms/2000000000", service, "{}");
        assertThat(empty.status()).as(empty.body()).isEqualTo(400);
        assertThat(empty.error()).isEqualTo("INVALID_BODY");
    }

    // ------------------------------------------------------------------ helpers

    private static void assertAffected(Response r) {
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().path("affected").asInt()).as(r.body()).isEqualTo(1);
    }

    private static String transferBody(int from, int to, String amount, String memo) {
        return "{\"fromAccountId\":" + from + ",\"toAccountId\":" + to
                + ",\"amount\":\"" + amount + "\",\"memo\":\"" + memo + "\"}";
    }

    private static String webhookBody(UUID owner, int accountId, String url) {
        return "{\"ownerUuid\":\"" + owner + "\",\"keyType\":\"PERSONAL\",\"accountId\":" + accountId
                + ",\"targetUrl\":\"" + url + "\",\"secret\":\"" + SECRET_HEX + "\"}";
    }

    /** A fresh GOVERNMENT account created in-game; returns its id. */
    private static int governmentAccount(String tag) {
        String admin = Usernames.fresh("AdG");
        String name = tag + Long.toHexString(Instant.now().toEpochMilli());
        try (MockPlayer a = MockPlayer.connectLocal(admin)) {
            Ledger.waitForPersonalBalance(a.getUuid(), TIMEOUT);
            int[] id = new int[1];
            Ops.run(admin, () -> {
                a.chatCommand("/gov account create " + name);
                id[0] = Ledger.waitForGovernmentAccount(name, TIMEOUT);
            });
            return id[0];
        }
    }

    /** A firm created in-game by {@code p}, optionally funded from their personal account. */
    private static int fundedFirm(MockPlayer p, String proprietor, String firm, String deposit) {
        int[] id = new int[1];
        Ops.run(proprietor, () -> {
            p.chatCommand("/business create " + firm);
            p.awaitMessageContaining("Successfully created", TIMEOUT);
            id[0] = BusinessLedger.waitForFirm(firm, TIMEOUT);
            if (deposit != null) {
                p.drainMessages();
                p.chatCommand("/business deposit " + firm + " " + deposit);
                p.awaitMessageContaining("Paid", TIMEOUT);
                BusinessLedger.waitForFirmDefaultBalance(id[0], new BigDecimal(deposit), TIMEOUT);
            }
        });
        return id[0];
    }

    private static Optional<String> accountColumn(int accountId, String column) {
        return queryString("SELECT " + column + " FROM accounts WHERE account_id = ?", accountId);
    }

    private static Optional<UUID> accountOwner(int accountId) {
        return queryString("SELECT LOWER(HEX(owner_uuid_bin)) FROM accounts WHERE account_id = ?", accountId)
                .map(h -> UUID.fromString(h.replaceFirst("(.{8})(.{4})(.{4})(.{4})(.{12})", "$1-$2-$3-$4-$5")));
    }

    private static Optional<String> webhookColumn(long id, String column) {
        return queryString("SELECT " + column + " FROM webhook_subscription WHERE subscription_id = ?", id);
    }

    private static int webhookCountFor(UUID owner) {
        return Integer.parseInt(queryString("SELECT COUNT(*) FROM webhook_subscription "
                + "WHERE owner_uuid_bin = UNHEX(REPLACE(?, '-', ''))", owner.toString()).orElse("0"));
    }

    private static Optional<BigDecimal> rateLimitMultiplier(UUID owner) {
        return queryString("SELECT multiplier FROM api_rate_limit_override "
                + "WHERE owner_uuid_bin = UNHEX(REPLACE(?, '-', ''))", owner.toString()).map(BigDecimal::new);
    }

    private static Optional<String> queryString(String sql, Object param) {
        try (Connection c = DriverManager.getConnection(DevServer.DB_URL, DevServer.DB_USER, DevServer.DB_PASSWORD);
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
