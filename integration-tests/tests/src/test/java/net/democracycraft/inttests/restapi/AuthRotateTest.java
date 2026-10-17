package net.democracycraft.inttests.restapi;

import com.fasterxml.jackson.databind.JsonNode;
import io.paradaux.mockclient.MockPlayer;
import net.democracycraft.inttests.support.ApiTokens;
import net.democracycraft.inttests.support.ApiTokens.IssuedKey;
import net.democracycraft.inttests.support.Ledger;
import net.democracycraft.inttests.support.RestApi;
import net.democracycraft.inttests.support.RestApi.Response;
import net.democracycraft.inttests.support.Usernames;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E coverage for {@code POST /api/v1/auth/rotate}. Drives the in-game
 * {@code /treasuryapi personal issue} command to obtain a real JWT, then
 * exercises the rotate endpoint against the running Spring Boot service.
 */
class AuthRotateTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void rotateReturnsNewTokenAndInvalidatesOld() {
        String username = Usernames.fresh("Ar");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            IssuedKey before = ApiTokens.issuePersonal(username, p);

            Response r = RestApi.postRotate(before.token());
            assertThat(r.status()).isEqualTo(200);

            JsonNode body = r.json();
            assertThat(body.path("keyId").asInt()).isEqualTo(before.keyId());
            String newToken = body.path("token").asText();
            assertThat(newToken).isNotEqualTo(before.token());
            assertThat(body.path("issuedAt").asText()).isNotBlank();
            assertThat(body.path("expiresAt").asText()).isNotBlank();

            // jti in DB rotates — old token now fails verification at step 7.
            IssuedKey after = ApiTokens.keyById(before.keyId());
            assertThat(after.jwtId()).isNotEqualTo(before.jwtId());
            // The rotated token is returned once and never written to the row.
            assertThat(after.token()).isNull();

            Response old = RestApi.postRotate(before.token());
            assertThat(old.status()).isEqualTo(401);
            assertThat(old.error()).isEqualTo("TOKEN_SUPERSEDED");

            // The new token rotates again successfully.
            Response again = RestApi.postRotate(newToken);
            assertThat(again.status()).isEqualTo(200);
        }
    }

    @Test
    void rotateRequiresAuthorizationHeader() {
        Response r = RestApi.postRotateNoAuth();
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.error()).isEqualTo("MISSING_TOKEN");
    }

    @Test
    void rotateRejectsMalformedToken() {
        Response r = RestApi.postRotate("not.a.jwt");
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.error()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void rotateRejectsRevokedToken() {
        String username = Usernames.fresh("Arv");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(username, p);
            ApiTokens.revokeInDb(key.keyId());

            Response r = RestApi.postRotate(key.token());
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.error()).isEqualTo("TOKEN_REVOKED");
        }
    }

    @Test
    void rotateRejectsSupersededToken() {
        String username = Usernames.fresh("Ars");
        try (MockPlayer p = MockPlayer.connectLocal(username)) {
            Ledger.waitForPersonalBalance(p.getUuid(), TIMEOUT);
            IssuedKey key = ApiTokens.issuePersonal(username, p);
            // Rotate jti server-side without changing the token signature.
            ApiTokens.poisonJtiInDb(key.keyId());

            Response r = RestApi.postRotate(key.token());
            assertThat(r.status()).isEqualTo(401);
            assertThat(r.error()).isEqualTo("TOKEN_SUPERSEDED");
        }
    }
}
