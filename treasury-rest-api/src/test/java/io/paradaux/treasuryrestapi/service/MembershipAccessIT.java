package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.TransactionsResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.security.VerifiedToken;
import io.paradaux.treasuryrestapi.testsupport.EmbeddedDbIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.nio.ByteBuffer;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Verifies the PAR-249 account_access switchover end-to-end for the REST API:
 * non-owner transaction-history access is gated by MembershipMapper.isMember,
 * which reads the consolidated {@code account_access} table. MEMBER/AUTHORIZER
 * grant access; VIEWER, revoked rows, and non-members do not. (The owner path
 * short-circuits before account_access, so it was previously untested here.)
 */
class MembershipAccessIT extends EmbeddedDbIT {

    @Autowired
    private TransactionService transactionService;

    private static final UUID OWNER    = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID MEMBER   = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID OUTSIDER = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @BeforeEach
    void seed() {
        insertAccount(1, "PERSONAL", OWNER, "acct"); // the account being read
    }

    /** A caller's PERSONAL token whose own account is #9 — so they're never the owner of #1. */
    private static VerifiedToken callerToken(UUID owner) {
        return new VerifiedToken(99L, owner, "PERSONAL", 9L, null);
    }

    @Test
    void member_canReadTransactions_viaAccountAccess() {
        grant(1, MEMBER, "MEMBER");
        TransactionsResponse resp = transactionService.getTransactions(callerToken(MEMBER), 1, 1, 100);
        assertThat(resp).isNotNull(); // access granted; empty history is fine
    }

    @Test
    void authorizer_canReadTransactions() {
        grant(1, MEMBER, "AUTHORIZER");
        assertThat(transactionService.getTransactions(callerToken(MEMBER), 1, 1, 100)).isNotNull();
    }

    @Test
    void viewerLevel_isForbidden() {
        grant(1, MEMBER, "VIEWER"); // a read-only VIEWER is not a member for history access
        assertForbidden(MEMBER);
    }

    @Test
    void nonMember_isForbidden() {
        assertForbidden(OUTSIDER); // no account_access row at all
    }

    @Test
    void revokedMember_isForbidden() {
        grant(1, MEMBER, "MEMBER");
        exec("UPDATE account_access SET removed_at = CURRENT_TIMESTAMP WHERE account_id = 1", ps -> { });
        assertForbidden(MEMBER);
    }

    private void grant(int accountId, UUID subject, String level) {
        exec("INSERT INTO account_access (account_id, subject_uuid_bin, level, added_by_uuid_bin) VALUES (?,?,?,?)",
                ps -> {
                    ps.setInt(1, accountId);
                    ps.setBytes(2, bytes(subject));
                    ps.setString(3, level);
                    ps.setBytes(4, bytes(OWNER));
                });
    }

    private void assertForbidden(UUID caller) {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> transactionService.getTransactions(callerToken(caller), 1, 1, 100));
        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static byte[] bytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }
}
