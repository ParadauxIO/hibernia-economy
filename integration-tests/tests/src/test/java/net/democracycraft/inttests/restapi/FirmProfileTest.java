package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class FirmProfileTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INVITE = "https://discord.gg/profile499";

    @Test
    void patchPreservesOmittedFieldsClearsBlanksAndRejectsInvalidBodiesAtomically() throws Exception {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("FpOwn"));
             MockPlayer reader = MockPlayer.connectLocal(Usernames.fresh("FpRe"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            Ledger.waitForPersonalBalance(reader.getUuid(), TIMEOUT);
            String name = Usernames.fresh("FpFrm");
            setup(owner, "/business create " + name);
            int id = BusinessLedger.waitForFirm(name, TIMEOUT);
            int account = BusinessLedger.firmDefaultAccountId(id).orElseThrow();
            setup(owner, "/paya account " + account + " 23.45 ProfileSeed");
            var whole = ApiTokens.issueBusiness(owner.getUsername(), owner, name);
            var scoped = ApiTokens.issueBusinessAccount(owner, name, account);
            var personal = ApiTokens.issuePersonal(reader.getUsername(), reader);
            long checkpoint = Ledger.transactionCheckpoint();
            profile(RestApi.getMyFirm(whole.token()), id, name, null, null);
            profile(patch(whole.token(), "{\"discordUrl\":\"  " + INVITE + "  \",\"hqRegion\":\" original499 \"}"), id, name, INVITE, "original499");
            stored(id, INVITE, "original499");
            profile(patch(whole.token(), "{\"hqRegion\":\"changed499\"}"), id, name, INVITE, "changed499");
            profile(patch(whole.token(), "{\"discordUrl\":\"" + INVITE + "\",\"hqRegion\":null}"), id, name, INVITE, "changed499");
            stored(id, INVITE, "changed499");
            var publicView = RestApi.call("GET", "/firms/" + name, personal.token(), null);
            assertThat(publicView.status()).isEqualTo(200);
            assertFields(publicView.json(), id, name, INVITE, "changed499");
            assertThat(publicView.json().path("defaultAccountId").asInt()).isEqualTo(account);
            assertThat(publicView.json().path("createdAt").asText()).isNotBlank();
            for (String token : new String[]{personal.token(), scoped.token(), null}) {
                var denied = patch(token, "{\"hqRegion\":\"forbidden499\"}");
                assertThat(denied.status()).isEqualTo(token == null ? 401 : 403);
                stored(id, INVITE, "changed499");
            }
            String limit = "h".repeat(64);
            profile(patch(whole.token(), JSON.writeValueAsString(Map.of("hqRegion", limit))), id, name, INVITE, limit);
            for (String invalid : new String[]{"{}", "{\"discordUrl\":null,\"hqRegion\":null}",
                    JSON.writeValueAsString(Map.of("hqRegion", "h".repeat(65), "discordUrl", "https://discord.gg/changed499")),
                    JSON.writeValueAsString(Map.of("hqRegion", "mustNotPersist", "discordUrl", "x".repeat(256)))}) {
                var rejected = patch(whole.token(), invalid);
                assertThat(rejected.status()).as("response %s", rejected.body()).isEqualTo(400);
                assertThat(rejected.error()).isEqualTo("INVALID_BODY");
                stored(id, INVITE, limit);
            }
            profile(patch(whole.token(), "{\"discordUrl\":\"\"}"), id, name, null, limit);
            profile(patch(whole.token(), "{\"hqRegion\":\"   \"}"), id, name, null, null);
            stored(id, null, null);
            profile(RestApi.getMyFirm(whole.token()), id, name, null, null);
            assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint);
            assertThat(Ledger.accountBalanceById(account).orElseThrow()).isEqualByComparingTo("23.45");
            assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("9976.55");
            assertThat(Ledger.personalBalance(reader.getUuid()).orElseThrow()).isEqualByComparingTo("10000");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "https://discord.gg/ab<red>bad", "https://example.com/abc"})
    void invalidDiscordInviteCannotReplaceStoredProfile(String invalid) throws Exception {
        try (MockPlayer owner = MockPlayer.connectLocal(Usernames.fresh("FiOwn"))) {
            Ledger.waitForPersonalBalance(owner.getUuid(), TIMEOUT);
            String name = Usernames.fresh("FiFrm");
            setup(owner, "/business create " + name);
            int id = BusinessLedger.waitForFirm(name, TIMEOUT);
            var key = ApiTokens.issueBusiness(owner.getUsername(), owner, name);
            profile(patch(key.token(), JSON.writeValueAsString(Map.of("discordUrl", INVITE))), id, name, INVITE, null);
            long checkpoint = Ledger.transactionCheckpoint();
            var response = patch(key.token(), JSON.writeValueAsString(Map.of("discordUrl", invalid)));
            assertAll(
                    () -> assertThat(response.status()).as("response %s", response.body()).isEqualTo(400),
                    () -> assertThat(BusinessLedger.firmDiscordUrl(id)).hasValue(INVITE),
                    () -> assertThat(Ledger.transactionCheckpoint()).isEqualTo(checkpoint),
                    () -> assertThat(Ledger.personalBalance(owner.getUuid()).orElseThrow()).isEqualByComparingTo("10000"));
        }
    }

    private static RestApi.Response patch(String token, String body) { return RestApi.call("PATCH", "/firms/me", token, body); }
    private static void profile(RestApi.Response response, int id, String name, String discord, String hq) {
        assertThat(response.status()).as("response %s", response.body()).isEqualTo(200);
        assertThat(response.json().size()).isEqualTo(5);
        assertFields(response.json(), id, name, discord, hq);
    }
    private static void assertFields(JsonNode json, int id, String name, String discord, String hq) {
        assertThat(json.path("firmId").asInt()).isEqualTo(id);
        assertThat(json.path("displayName").asText()).isEqualTo(name);
        assertThat(json.path("archived").asBoolean()).isFalse();
        if (discord == null) assertThat(json.path("discordUrl").isNull()).isTrue();
        else assertThat(json.path("discordUrl").asText()).isEqualTo(discord);
        if (hq == null) assertThat(json.path("hqRegion").isNull()).isTrue();
        else assertThat(json.path("hqRegion").asText()).isEqualTo(hq);
    }
    private static void stored(int id, String discord, String hq) {
        assertThat(BusinessLedger.firmDiscordUrl(id).orElse(null)).isEqualTo(discord);
        assertThat(BusinessLedger.firmHqRegion(id).orElse(null)).isEqualTo(hq);
    }
    private static void setup(MockPlayer player, String command) {
        Ops.run(player.getUsername(), () -> { player.chatCommand(command); Barrier.settle(); });
    }
}
