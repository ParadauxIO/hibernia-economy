package io.paradaux.treasuryrestapi.controller;

import io.jsonwebtoken.Jwts;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the error codes a client sees for a malformed write request, over real HTTP
 * (auth filter → {@code @Valid} → {@code GlobalExceptionHandler}).
 *
 * <p>These endpoints returned {@code INVALID_AMOUNT} / {@code INVALID_BODY} /
 * {@code INVALID_WEBHOOK_URL} from the service layer before bean validation was
 * added to the request DTOs. Bean validation now rejects the same requests earlier,
 * and must keep answering with the same codes — API consumers branch on them.
 */
class RequestValidationErrorCodeIT extends EmbeddedDbIT {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String JTI = "validation-it-jti";

    @Autowired private SecretKey signingKey;

    private String bearer;

    @BeforeEach
    void seedKey() {
        insertApiKey(1, "PERSONAL", OWNER, JTI, false, LocalDateTime.now().plusDays(30));
        bearer = "Bearer " + Jwts.builder()
                .header().add("kid", "1").and()
                .subject(OWNER.toString())
                .claim("type", "PERSONAL")
                .claim("acc", 1)
                .id(JTI)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.DAYS)))
                .signWith(signingKey, Jwts.SIG.HS256).compact();
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path)
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void expectBadRequest(String path, String body, String code) throws Exception {
        postJson(path, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(code));
    }

    // ── POST /transfers ─────────────────────────────────────────────────────────

    @Test
    void transfer_missingAmount_isInvalidAmount() throws Exception {
        expectBadRequest("/api/v1/transfers", "{\"toAccountId\":2}", "INVALID_AMOUNT");
    }

    @Test
    void transfer_blankAmount_isInvalidAmount() throws Exception {
        expectBadRequest("/api/v1/transfers", "{\"toAccountId\":2,\"amount\":\"  \"}", "INVALID_AMOUNT");
    }

    @Test
    void transfer_missingToAccountId_isInvalidBody() throws Exception {
        expectBadRequest("/api/v1/transfers", "{\"amount\":\"1.00\"}", "INVALID_BODY");
    }

    @Test
    void transfer_missingDestinationAndAmount_reportsTheDestinationFirst() throws Exception {
        // Both fields fail; the service checked the destination before the amount,
        // and the answer must not depend on validator iteration order.
        expectBadRequest("/api/v1/transfers", "{}", "INVALID_BODY");
    }

    @Test
    void transfer_overlongMemo_isInvalidBody() throws Exception {
        expectBadRequest("/api/v1/transfers",
                "{\"toAccountId\":2,\"amount\":\"1.00\",\"memo\":\"" + "m".repeat(256) + "\"}", "INVALID_BODY");
    }

    @Test
    void transfer_unparseableAmount_isStillInvalidAmountFromTheService() throws Exception {
        expectBadRequest("/api/v1/transfers", "{\"toAccountId\":2,\"amount\":\"abc\"}", "INVALID_AMOUNT");
    }

    @Test
    void transfer_missingOrMalformedBody_isInvalidBody() throws Exception {
        expectBadRequest("/api/v1/transfers", "", "INVALID_BODY");
        expectBadRequest("/api/v1/transfers", "{not json", "INVALID_BODY");
    }

    // ── POST /transfers/to-firm, /transfers/to-player ───────────────────────────

    @Test
    void transferToFirm_missingFirm_isInvalidBody() throws Exception {
        expectBadRequest("/api/v1/transfers/to-firm", "{\"amount\":\"1.00\"}", "INVALID_BODY");
    }

    @Test
    void transferToFirm_missingAmount_isInvalidAmount() throws Exception {
        expectBadRequest("/api/v1/transfers/to-firm", "{\"toFirm\":\"Acme\"}", "INVALID_AMOUNT");
    }

    @Test
    void transferToPlayer_missingAmount_isInvalidAmount() throws Exception {
        expectBadRequest("/api/v1/transfers/to-player",
                "{\"toPlayerUuid\":\"22222222-2222-2222-2222-222222222222\"}", "INVALID_AMOUNT");
    }

    // ── POST /webhooks ──────────────────────────────────────────────────────────

    @Test
    void createWebhook_missingUrl_isInvalidWebhookUrl() throws Exception {
        expectBadRequest("/api/v1/webhooks", "{}", "INVALID_WEBHOOK_URL");
    }
}
