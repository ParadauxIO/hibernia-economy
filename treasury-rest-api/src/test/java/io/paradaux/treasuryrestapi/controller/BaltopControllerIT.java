package io.paradaux.treasuryrestapi.controller;

import io.jsonwebtoken.Jwts;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the real authentication filter, request binding, service and SQL. */
class BaltopControllerIT extends EmbeddedDbIT {

    private static final String PATH = "/api/v1/accounts/baltop";

    @Autowired
    private SecretKey signingKey;

    private String bearerToken() {
        return bearerToken(1, UUID.randomUUID(), "PERSONAL");
    }

    private String bearerToken(int keyId, UUID owner, String keyType) {
        String jwtId = UUID.randomUUID().toString();
        insertApiKey(keyId, keyType, owner, jwtId, false, LocalDateTime.now().plusHours(1));
        return "Bearer " + Jwts.builder()
                .header().keyId(Integer.toString(keyId)).and()
                .subject(owner.toString())
                .id(jwtId)
                .claim("type", keyType)
                .claim("BUSINESS".equals(keyType) ? "firm" : "acc", 1L)
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PERSONAL", "BUSINESS"})
    void rateLimit_isTenPerMinuteAndSharedAcrossIssuerKeys(String keyType) throws Exception {
        UUID issuer = UUID.randomUUID();
        String token = bearerToken(1, issuer, keyType);
        String otherKey = bearerToken(2, issuer, "BUSINESS".equals(keyType) ? "PERSONAL" : "BUSINESS");
        for (int i = 0; i < 10; i++) {
            mvc.perform(get(PATH).header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Limit", "10"));
        }

        // Switching key (and key type) must not grant the same issuer a fresh budget.
        mvc.perform(get(PATH).header("Authorization", otherKey))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Limit", "10"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void missingToken_returns401() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("MISSING_TOKEN"));
    }

    @Test
    void personalKey_returnsOtherPlayersWithDefaultPagination() throws Exception {
        UUID richest = null;
        for (int id = 1; id <= 11; id++) {
            UUID player = UUID.randomUUID();
            insertAccount(id, "PERSONAL", player, "Player" + id);
            seedBalance(id, id + ".00");
            richest = player;
        }

        mvc.perform(get(PATH).header("Authorization", bearerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.totalItems").value(11))
                .andExpect(jsonPath("$.items.length()").value(10))
                .andExpect(jsonPath("$.items[0].rank").value(1))
                .andExpect(jsonPath("$.items[0].accountId").value(11))
                .andExpect(jsonPath("$.items[0].playerUuid").value(richest.toString()))
                .andExpect(jsonPath("$.items[0].playerName").value("Player11"))
                .andExpect(jsonPath("$.items[0].balance").value("11.00"))
                .andExpect(jsonPath("$.items[9].rank").value(10));
    }

    @ParameterizedTest
    @CsvSource({"0,10", "-1,10", "1,0", "1,-1", "1,101", "21474840,100", "abc,10", "1,abc"})
    void invalidPagination_returns400(String page, String limit) throws Exception {
        mvc.perform(get(PATH).header("Authorization", bearerToken())
                        .param("page", page).param("limit", limit))
                .andExpect(status().isBadRequest());
    }
}
