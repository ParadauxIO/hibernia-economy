package io.paradaux.treasuryrestapi.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.jsonwebtoken.Jwts;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

class FirmAccountLookupIT extends EmbeddedDbIT {
    @Autowired
    private SecretKey signingKey;

    private String token(String type) {
        UUID owner = UUID.randomUUID();
        String jwtId = UUID.randomUUID().toString();
        insertApiKey(
                1,
                type,
                owner,
                jwtId,
                false,
                LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(1));
        return "Bearer "
                + Jwts.builder()
                        .header()
                        .keyId("1")
                        .and()
                        .subject(owner.toString())
                        .id(jwtId)
                        .claim("type", type)
                        .claim("BUSINESS".equals(type) ? "firm" : "acc", 99L)
                        .expiration(Date.from(Instant.now().plusSeconds(3600)))
                        .signWith(signingKey, Jwts.SIG.HS256)
                        .compact();
    }

    private void seed() {
        insertAccount(1, "BUSINESS", null, "Main");
        insertAccount(2, "BUSINESS", null, "Reserve");
        insertAccount(3, "BUSINESS", null, "Removed");
        insertAccount(4, "BUSINESS", null, "Other firm");
        insertFirm(10, "Acme", null, 2);
        insertFirm(20, "10");
        linkFirmAccount(10, 1);
        linkFirmAccount(10, 2);
        linkFirmAccount(10, 3);
        linkFirmAccount(20, 4);
        exec("UPDATE firm_accounts SET removed_at = CURRENT_TIMESTAMP WHERE account_id = 3", ps -> {});
    }

    @ParameterizedTest
    @ValueSource(strings = {"PERSONAL", "BUSINESS"})
    void anyAuthenticatedCaller_canListAndReverseLookupOtherFirms(String type) throws Exception {
        seed();
        String auth = token(type);
        for (String path : new String[] {"/api/v1/firms/Acme/accounts", "/api/v1/firms/by-id/10/accounts"}) {
            mvc.perform(get(path).header("Authorization", auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].accountId").value(1))
                    .andExpect(jsonPath("$[0].displayName").value("Main"))
                    .andExpect(jsonPath("$[0].defaultAccount").value(false))
                    .andExpect(jsonPath("$[1].accountId").value(2))
                    .andExpect(jsonPath("$[1].defaultAccount").value(true));
        }
        mvc.perform(get("/api/v1/accounts/1/firm").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firmId").value(10))
                .andExpect(jsonPath("$.displayName").value("Acme"));
        mvc.perform(get("/api/v1/firms/10/accounts").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].accountId").value(4))
                .andExpect(jsonPath("$[0].defaultAccount").value(false));
    }

    @Test
    void missingResourcesAndRemovedLinks_haveDistinctErrors() throws Exception {
        seed();
        insertAccount(5, "PERSONAL", null, "Player");
        String auth = token("PERSONAL");
        for (String path : new String[] {"/api/v1/firms/Ghost/accounts", "/api/v1/firms/by-id/999/accounts"}) {
            mvc.perform(get(path).header("Authorization", auth))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("FIRM_NOT_FOUND"));
        }
        mvc.perform(get("/api/v1/accounts/999/firm").header("Authorization", auth))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"));
        for (int id : new int[] {3, 5}) {
            mvc.perform(get("/api/v1/accounts/" + id + "/firm").header("Authorization", auth))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("ACCOUNT_FIRM_NOT_FOUND"));
        }
        // A reassigned, restored link must resolve to its current firm.
        exec("UPDATE firm_accounts SET firm_id = 20, removed_at = NULL WHERE account_id = 3", ps -> {});
        mvc.perform(get("/api/v1/accounts/3/firm").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firmId").value(20));
    }

    @Test
    void emptyFirmAndArchivedLinkedAccount_followCurrentLinks() throws Exception {
        seed();
        insertFirm(30, "Empty");
        exec("UPDATE accounts SET is_archived = 1 WHERE account_id = 1", ps -> {});
        String auth = token("PERSONAL");
        mvc.perform(get("/api/v1/firms/Empty/accounts").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/firms/Acme/accounts").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].accountId").value(1));
        mvc.perform(get("/api/v1/accounts/1/firm").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firmId").value(10));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"/api/v1/firms/Acme/accounts", "/api/v1/firms/by-id/10/accounts", "/api/v1/accounts/1/firm"})
    void unauthenticatedCaller_isRejected(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }
}
