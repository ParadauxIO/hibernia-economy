package io.paradaux.business.mappers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paradaux.business.model.Firm;
import io.paradaux.business.testsupport.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.UUID;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FirmRequestMapperIT extends IntegrationTestBase {

    private FirmRequestMapper requests;
    private int firmId;

    private final UUID inviter = UUID.randomUUID();
    private final UUID invited = UUID.randomUUID();

    @BeforeEach
    void seed() {
        requests = mapper(FirmRequestMapper.class);
        FirmMapper firms = mapper(FirmMapper.class);

        Firm f = new Firm();
        f.setDisplayName("Acme");
        f.setProprietorUuid(inviter.toString());
        firms.createFirm(f);
        firmId = f.getFirmId();
    }

    /**
     * "Now" as the database sees it. Every expiry predicate in the mapper compares
     * {@code expires_at} with the server's {@code NOW()}, so fixtures must be built on
     * the same clock: a JVM {@code LocalDateTime.now()} is only comparable when the JVM
     * and the DB session share a time zone, and these tests failed whenever they didn't
     * (e.g. a UTC CI database with a non-UTC developer JVM, or vice versa).
     */
    private LocalDateTime dbNow() {
        try (var conn = dataSource.getConnection();
                var st = conn.createStatement();
                var rs = st.executeQuery("SELECT NOW()")) {
            rs.next();
            return rs.getObject(1, LocalDateTime.class);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- invites ----------

    @Test
    void createInvite_andHasPending() {
        int rows = requests.createInvite(firmId, invited.toString(), inviter.toString(), dbNow().plusMinutes(5));
        assertThat(rows).isEqualTo(1);
        assertThat(requests.hasPendingJobOffer(firmId, invited.toString())).isTrue();
    }

    @Test
    void createInvite_secondPendingIsRejected() {
        requests.createInvite(firmId, invited.toString(), inviter.toString(), dbNow().plusMinutes(5));
        assertThatThrownBy(() ->
                        requests.createInvite(firmId, invited.toString(), inviter.toString(), dbNow().plusMinutes(5)))
                .isInstanceOf(PersistenceException.class);
    }

    /**
     * A lapsed invite is still PENDING until something expires it, so it keeps
     * occupying uq_one_pending_invite even though hasPendingJobOffer no longer sees
     * it. expireStaleInvite must free the slot so the firm can re-offer.
     */
    @Test
    void expireStaleInvite_freesTheUniqueSlotForAReoffer() {
        requests.createInvite(firmId, invited.toString(), inviter.toString(), dbNow().minusMinutes(1));
        assertThat(requests.hasPendingJobOffer(firmId, invited.toString())).isFalse();
        // The bug: the guard says "no pending offer" but the insert collides.
        assertThatThrownBy(() ->
                        requests.createInvite(firmId, invited.toString(), inviter.toString(), dbNow().plusMinutes(5)))
                .isInstanceOf(PersistenceException.class);

        assertThat(requests.expireStaleInvite(firmId, invited.toString())).isEqualTo(1);

        assertThat(requests.createInvite(firmId, invited.toString(), inviter.toString(), dbNow().plusMinutes(5)))
                .isEqualTo(1);
        assertThat(requests.hasPendingJobOffer(firmId, invited.toString())).isTrue();
    }

    @Test
    void expireStaleInvite_leavesALiveInviteAlone() {
        requests.createInvite(firmId, invited.toString(), inviter.toString(), dbNow().plusMinutes(5));
        assertThat(requests.expireStaleInvite(firmId, invited.toString())).isZero();
        assertThat(requests.hasPendingJobOffer(firmId, invited.toString())).isTrue();
    }

    @Test
    void acceptInvite_flipsStatus() {
        requests.createInvite(firmId, invited.toString(), inviter.toString(), dbNow().plusMinutes(5));
        assertThat(requests.acceptInvite(firmId, invited.toString())).isEqualTo(1);
        assertThat(requests.hasPendingJobOffer(firmId, invited.toString())).isFalse();
        assertThat(requests.getInviter(firmId, invited.toString())).isEqualToIgnoringCase(inviter.toString());
    }

    @Test
    void rejectInvite_flipsStatus() {
        requests.createInvite(firmId, invited.toString(), inviter.toString(), dbNow().plusMinutes(5));
        assertThat(requests.rejectInvite(firmId, invited.toString())).isEqualTo(1);
        assertThat(requests.hasPendingJobOffer(firmId, invited.toString())).isFalse();
    }

    @Test
    void rescindInvite_flipsStatus() {
        requests.createInvite(firmId, invited.toString(), inviter.toString(), dbNow().plusMinutes(5));
        assertThat(requests.rescindInvite(firmId, invited.toString())).isEqualTo(1);
        assertThat(requests.hasPendingJobOffer(firmId, invited.toString())).isFalse();
    }

    @Test
    void expireStaleInvites_marksPastInvitesExpired() {
        requests.createInvite(firmId, invited.toString(), inviter.toString(), dbNow().minusMinutes(1));
        // hasPendingJobOffer ignores expired ones
        assertThat(requests.hasPendingJobOffer(firmId, invited.toString())).isFalse();

        int affected = requests.expireStaleInvites();
        assertThat(affected).isEqualTo(1);
    }

    @Test
    void lockPendingInviter_returnsInviter() {
        requests.createInvite(firmId, invited.toString(), inviter.toString(), dbNow().plusMinutes(5));
        assertThat(requests.lockPendingInviter(firmId, invited.toString())).isEqualToIgnoringCase(inviter.toString());
    }

    // ---------- transfer requests ----------

    @Test
    void createAndConfirmTransfer() {
        int rows = requests.createTransferRequest(firmId, invited.toString(), "tok-1", dbNow().plusMinutes(5));
        assertThat(rows).isEqualTo(1);
        assertThat(requests.confirmTransfer(firmId, invited.toString(), "tok-1"))
                .isEqualTo(1);
    }

    @Test
    void rejectTransfer_setsCancelled() {
        requests.createTransferRequest(firmId, invited.toString(), "tok-1", dbNow().plusMinutes(5));
        assertThat(requests.rejectTransfer(firmId, invited.toString())).isEqualTo(1);
    }

    @Test
    void acceptTransfer_requiresConfirmedState() {
        requests.createTransferRequest(firmId, invited.toString(), "tok-1", dbNow().plusMinutes(5));
        // PENDING -> ACCEPTED should NOT update (only CONFIRMED -> ACCEPTED).
        assertThat(requests.acceptTransfer(firmId, invited.toString())).isZero();
        requests.confirmTransfer(firmId, invited.toString(), "tok-1");
        assertThat(requests.acceptTransfer(firmId, invited.toString())).isEqualTo(1);
    }

    /**
     * A lapsed transfer is still active until something expires it, so it keeps
     * occupying uq_one_active_transfer while rejectTransfer (unexpired only) can't
     * cancel it. expireStaleTransfer must free the slot for a new transfer.
     */
    @Test
    void expireStaleTransfer_freesTheUniqueSlotForANewTransfer() {
        requests.createTransferRequest(firmId, invited.toString(), "tok-lapsed", dbNow().minusMinutes(1));
        assertThat(requests.rejectTransfer(firmId, invited.toString())).isZero();
        assertThatThrownBy(() ->
                        requests.createTransferRequest(firmId, invited.toString(), "tok-new", dbNow().plusMinutes(5)))
                .isInstanceOf(PersistenceException.class);

        assertThat(requests.expireStaleTransfer(firmId)).isEqualTo(1);

        assertThat(requests.createTransferRequest(firmId, invited.toString(), "tok-new", dbNow().plusMinutes(5)))
                .isEqualTo(1);
    }

    @Test
    void expireStaleTransfer_leavesALiveTransferAlone() {
        requests.createTransferRequest(firmId, invited.toString(), "tok-live", dbNow().plusMinutes(5));
        assertThat(requests.expireStaleTransfer(firmId)).isZero();
        assertThat(requests.confirmTransfer(firmId, invited.toString(), "tok-live"))
                .isEqualTo(1);
    }

    @Test
    void expireStaleTransfers_marksOverdue() {
        requests.createTransferRequest(firmId, invited.toString(), "tok-1", dbNow().minusMinutes(1));
        assertThat(requests.expireStaleTransfers()).isEqualTo(1);
    }

    // ---------- admin proprietor override audit (PAR-315, V26) ----------

    @Test
    void recordAdminOverride_writesAuditRowWithActor() throws Exception {
        UUID from = inviter; // current proprietor from seed()
        UUID to = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        assertThat(requests.recordAdminOverride(
                        firmId, from.toString(), to.toString(), actor.toString(), "override-tok-1"))
                .isEqualTo(1);

        try (var conn = dataSource.getConnection();
                var ps = conn.prepareStatement("SELECT status, bin_to_uuid(from_uuid_bin), bin_to_uuid(to_uuid_bin), "
                        + "bin_to_uuid(actor_uuid_bin) FROM firm_transfer_requests "
                        + "WHERE firm_id = ? AND status = 'ADMIN_OVERRIDE'")) {
            ps.setInt(1, firmId);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("ADMIN_OVERRIDE");
                assertThat(rs.getString(2)).isEqualToIgnoringCase(from.toString());
                assertThat(rs.getString(3)).isEqualToIgnoringCase(to.toString());
                assertThat(rs.getString(4)).isEqualToIgnoringCase(actor.toString());
            }
        }

        // A terminal ADMIN_OVERRIDE has active_only = NULL, so a second override on
        // the same firm must not collide with uq_one_active_transfer.
        assertThat(requests.recordAdminOverride(
                        firmId, from.toString(), to.toString(), actor.toString(), "override-tok-2"))
                .isEqualTo(1);
    }

    /**
     * The override audit row is written with expires_at = now, so it is "overdue"
     * from the moment it exists. The expiry job must only touch in-flight
     * (PENDING / CONFIRMED) transfers, or it rewrites the audit trail to EXPIRED.
     */
    @Test
    void expireStaleTransfers_leavesAdminOverrideAuditRowsIntact() throws Exception {
        requests.recordAdminOverride(
                firmId,
                inviter.toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "override-tok-3");
        // Backdate it so the row is unambiguously past its expiry.
        try (var conn = dataSource.getConnection();
                var ps = conn.prepareStatement(
                        "UPDATE firm_transfer_requests SET expires_at = NOW() - INTERVAL 1 HOUR WHERE firm_id = ?")) {
            ps.setInt(1, firmId);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        // An overdue in-flight transfer alongside it IS expired.
        requests.createTransferRequest(firmId, invited.toString(), "tok-overdue", dbNow().minusMinutes(1));

        assertThat(requests.expireStaleTransfers()).isEqualTo(1);

        try (var conn = dataSource.getConnection();
                var ps = conn.prepareStatement(
                        "SELECT token, status FROM firm_transfer_requests WHERE firm_id = ? ORDER BY token")) {
            ps.setInt(1, firmId);
            try (var rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("override-tok-3");
                assertThat(rs.getString(2)).isEqualTo("ADMIN_OVERRIDE");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("tok-overdue");
                assertThat(rs.getString(2)).isEqualTo("EXPIRED");
            }
        }
    }
}
