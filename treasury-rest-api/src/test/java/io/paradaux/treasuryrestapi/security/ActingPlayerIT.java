package io.paradaux.treasuryrestapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import io.paradaux.treasuryrestapi.testsupport.TestTokens;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * {@code X-Acting-Player} end to end through the security chain and the admin
 * interceptor (PAR-542): an admin transfer records the acting player as the
 * ledger initiator, falls back to the SERVICE key's owner without one, and a
 * malformed header is refused before any money moves.
 */
class ActingPlayerIT extends EmbeddedDbIT {

    private static final UUID ACTING = UUID.fromString("0b5a1c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d");
    private static final String TRANSFER =
            "{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":\"25.00\",\"memo\":\"admin move\"}";

    @Autowired
    private SecretKey signingKey;

    private TestTokens tokens;

    @BeforeEach
    void seed() {
        tokens = new TestTokens(signingKey, this::insertApiKey);
        tokens.seedAll();
        insertAccount(1, "BUSINESS", null, "Source");
        insertAccount(2, "BUSINESS", null, "Destination");
        seedBalance(1, "100.00");
        seedBalance(2, "0.00");
    }

    private ResultActions transfer(String bearer, String actingPlayer) throws Exception {
        var req = post("/api/v1/admin/transfers")
                .header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(TRANSFER);
        if (actingPlayer != null) req.header(ActingPlayer.HEADER, actingPlayer);
        return mvc.perform(req);
    }

    private UUID initiatorOfLastTxn() throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps =
                        c.prepareStatement("SELECT initiator_uuid_bin FROM ledger_txns ORDER BY txn_id DESC LIMIT 1");
                ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return null;
            ByteBuffer bb = ByteBuffer.wrap(rs.getBytes(1));
            return new UUID(bb.getLong(), bb.getLong());
        }
    }

    @Test
    void adminTransfer_recordsTheActingPlayerAsInitiator() throws Exception {
        transfer(tokens.service(), ACTING.toString()).andExpect(status().isOk());
        assertThat(initiatorOfLastTxn()).isEqualTo(ACTING);
        assertThat(sumPostings(2)).isEqualByComparingTo("25.00");
    }

    @Test
    void adminTransfer_withoutTheHeader_keepsTheServiceKeyOwner() throws Exception {
        transfer(tokens.service(), null).andExpect(status().isOk());
        assertThat(initiatorOfLastTxn()).isEqualTo(TestTokens.SERVICE_OWNER);
    }

    @Test
    void malformedHeader_isA400_andMovesNoMoney() throws Exception {
        transfer(tokens.service(), "not-a-player")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ACTING_PLAYER"));
        assertThat(rowCount("ledger_txns")).isZero();
    }

    @Test
    void nonServiceToken_isStillForbidden_whateverTheHeaderSays() throws Exception {
        transfer(tokens.personal(), ACTING.toString()).andExpect(status().isForbidden());
        transfer(tokens.personal(), "not-a-player").andExpect(status().isForbidden());
        assertThat(rowCount("ledger_txns")).isZero();
    }

    @Test
    void otherAdminEndpoints_acceptTheHeader() throws Exception {
        mvc.perform(patch("/api/v1/admin/accounts/1/display-name")
                        .header("Authorization", "Bearer " + tokens.service())
                        .header(ActingPlayer.HEADER, ACTING.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Renamed"));
    }

    @Test
    void publicEndpoints_ignoreTheHeader() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/config/tax")
                        .header(ActingPlayer.HEADER, "not-a-player"))
                .andExpect(status().isOk());
    }
}
