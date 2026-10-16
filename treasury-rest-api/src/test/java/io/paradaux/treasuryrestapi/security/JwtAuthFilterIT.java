package io.paradaux.treasuryrestapi.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import io.paradaux.treasuryrestapi.testsupport.TestTokens;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@link JwtAuthFilter}'s Authorization-header parsing, over real HTTP (MockMvc
 * through the full security chain). The verifier's claim pipeline is unit-tested in
 * {@link JwtTokenVerifierTest}; this pins what the filter does <em>before</em> it
 * gets there — every malformed header must be a clean 401 in the error envelope,
 * never a 500 and never a pass-through.
 */
class JwtAuthFilterIT extends EmbeddedDbIT {

    /** Any authenticated, non-scoped endpoint: answers 200 with the caller's key. */
    private static final String ME = "/api/v1/auth/me";

    @Autowired
    private SecretKey signingKey;

    private TestTokens tokens;

    @BeforeEach
    void seedKeys() {
        tokens = new TestTokens(signingKey, this::insertApiKey);
        tokens.seedAll();
    }

    private ResultActions me(String... authorization) throws Exception {
        var req = get(ME);
        if (authorization.length > 0) req.header("Authorization", (Object[]) authorization);
        return mvc.perform(req);
    }

    private void expect401(ResultActions r, String code) throws Exception {
        r.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value(code));
    }

    @Test
    void validBearer_isAuthenticated() throws Exception {
        me("Bearer " + tokens.personal())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keyType").value("PERSONAL"));
    }

    @Test
    void noHeader_isMissingToken() throws Exception {
        expect401(me(), "MISSING_TOKEN");
    }

    /** Anything that isn't literally {@code "Bearer "} + token is treated as no token at all. */
    @ParameterizedTest
    @ValueSource(strings = {"", "Bearer", "Token abc", "Basic dXNlcjpwYXNz", "Bearerabc"})
    void headerWithoutBearerPrefix_isMissingToken(String header) throws Exception {
        expect401(me(header), "MISSING_TOKEN");
    }

    /**
     * The scheme match is case-sensitive ({@code startsWith("Bearer ")}). RFC 7235 makes
     * auth schemes case-insensitive, but every client we ship (plugins, explorer) sends
     * {@code Bearer}; pinned so a change here is deliberate.
     */
    @Test
    void lowercaseScheme_isMissingToken() throws Exception {
        expect401(me("bearer " + tokens.personal()), "MISSING_TOKEN");
    }

    /**
     * {@code "Bearer "} with nothing (or only whitespace) after it. jjwt rejects an empty
     * compact string with IllegalArgumentException rather than a JwtException, which
     * used to escape the filter as a 500; it must be a 401.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Bearer ", "Bearer    ", "Bearer \t"})
    void emptyToken_isMissingToken(String header) throws Exception {
        expect401(me(header), "MISSING_TOKEN");
    }

    /** No trimming: stray whitespace around the token makes it unparseable, not accepted. */
    @Test
    void extraWhitespaceAroundToken_isInvalidToken() throws Exception {
        expect401(me("Bearer  " + tokens.personal()), "INVALID_TOKEN");
        expect401(me("Bearer " + tokens.personal() + " "), "INVALID_TOKEN");
    }

    @Test
    void garbageToken_isInvalidToken() throws Exception {
        expect401(me("Bearer not-a-jwt"), "INVALID_TOKEN");
        expect401(me("Bearer a.b.c"), "INVALID_TOKEN");
    }

    @Test
    void expiredToken_isTokenExpired() throws Exception {
        expect401(me("Bearer " + tokens.expiredPersonal()), "TOKEN_EXPIRED");
    }

    @Test
    void wrongSigningKey_isInvalidToken() throws Exception {
        expect401(me("Bearer " + tokens.personalSignedWithWrongKey()), "INVALID_TOKEN");
    }

    @Test
    void unsignedAlgNoneToken_isInvalidToken() throws Exception {
        expect401(me("Bearer " + tokens.personalUnsigned()), "INVALID_TOKEN");
    }

    /**
     * Repeated Authorization headers: only the first is read ({@code getHeader}). A valid
     * token can't be smuggled past an invalid first header, and a trailing junk header
     * doesn't break a valid first one.
     */
    @Test
    void multipleHeaders_onlyFirstIsUsed() throws Exception {
        expect401(me("Bearer not-a-jwt", "Bearer " + tokens.personal()), "INVALID_TOKEN");
        me("Bearer " + tokens.personal(), "Bearer not-a-jwt").andExpect(status().isOk());
    }

    /** Public prefixes skip the filter entirely, so even a broken token can't 401 them. */
    @Test
    void publicRoute_ignoresInvalidToken() throws Exception {
        mvc.perform(get("/api/v1/config/tax").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isOk());
    }
}
