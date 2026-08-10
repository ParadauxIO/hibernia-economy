package io.paradaux.business.mappers;

import io.paradaux.business.model.Firm;
import io.paradaux.business.testsupport.IntegrationTestBase;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // ---------- invites ----------

    @Test
    void createInvite_andHasPending() {
        int rows = requests.createInvite(firmId, invited.toString(), inviter.toString(),
                LocalDateTime.now().plusMinutes(5));
        assertThat(rows).isEqualTo(1);
        assertThat(requests.hasPendingJobOffer(firmId, invited.toString())).isTrue();
    }

    @Test
    void createInvite_secondPendingIsRejected() {
        requests.createInvite(firmId, invited.toString(), inviter.toString(),
                LocalDateTime.now().plusMinutes(5));
        assertThatThrownBy(() ->
                requests.createInvite(firmId, invited.toString(), inviter.toString(),
                        LocalDateTime.now().plusMinutes(5)))
                .isInstanceOf(PersistenceException.class);
    }

    @Test
    void acceptInvite_flipsStatus() {
        requests.createInvite(firmId, invited.toString(), inviter.toString(),
                LocalDateTime.now().plusMinutes(5));
        assertThat(requests.acceptInvite(firmId, invited.toString())).isEqualTo(1);
        assertThat(requests.hasPendingJobOffer(firmId, invited.toString())).isFalse();
        assertThat(requests.getInviter(firmId, invited.toString())).isEqualToIgnoringCase(inviter.toString());
    }

    @Test
    void rejectInvite_flipsStatus() {
        requests.createInvite(firmId, invited.toString(), inviter.toString(),
                LocalDateTime.now().plusMinutes(5));
        assertThat(requests.rejectInvite(firmId, invited.toString())).isEqualTo(1);
        assertThat(requests.hasPendingJobOffer(firmId, invited.toString())).isFalse();
    }

    @Test
    void rescindInvite_flipsStatus() {
        requests.createInvite(firmId, invited.toString(), inviter.toString(),
                LocalDateTime.now().plusMinutes(5));
        assertThat(requests.rescindInvite(firmId, invited.toString())).isEqualTo(1);
        assertThat(requests.hasPendingJobOffer(firmId, invited.toString())).isFalse();
    }

    @Test
    void expireStaleInvites_marksPastInvitesExpired() {
        requests.createInvite(firmId, invited.toString(), inviter.toString(),
                LocalDateTime.now().minusMinutes(1));
        // hasPendingJobOffer ignores expired ones
        assertThat(requests.hasPendingJobOffer(firmId, invited.toString())).isFalse();

        int affected = requests.expireStaleInvites();
        assertThat(affected).isEqualTo(1);
    }

    @Test
    void lockPendingInviter_returnsInviter() {
        requests.createInvite(firmId, invited.toString(), inviter.toString(),
                LocalDateTime.now().plusMinutes(5));
        assertThat(requests.lockPendingInviter(firmId, invited.toString()))
                .isEqualToIgnoringCase(inviter.toString());
    }

    // ---------- transfer requests ----------

    @Test
    void createAndConfirmTransfer() {
        int rows = requests.createTransferRequest(firmId, invited.toString(), "tok-1",
                LocalDateTime.now().plusMinutes(5));
        assertThat(rows).isEqualTo(1);
        assertThat(requests.confirmTransfer(firmId, invited.toString(), "tok-1")).isEqualTo(1);
    }

    @Test
    void rejectTransfer_setsCancelled() {
        requests.createTransferRequest(firmId, invited.toString(), "tok-1",
                LocalDateTime.now().plusMinutes(5));
        assertThat(requests.rejectTransfer(firmId, invited.toString())).isEqualTo(1);
    }

    @Test
    void acceptTransfer_requiresConfirmedState() {
        requests.createTransferRequest(firmId, invited.toString(), "tok-1",
                LocalDateTime.now().plusMinutes(5));
        // PENDING -> ACCEPTED should NOT update (only CONFIRMED -> ACCEPTED).
        assertThat(requests.acceptTransfer(firmId, invited.toString())).isZero();
        requests.confirmTransfer(firmId, invited.toString(), "tok-1");
        assertThat(requests.acceptTransfer(firmId, invited.toString())).isEqualTo(1);
    }

    @Test
    void expireStaleTransfers_marksOverdue() {
        requests.createTransferRequest(firmId, invited.toString(), "tok-1",
                LocalDateTime.now().minusMinutes(1));
        assertThat(requests.expireStaleTransfers()).isEqualTo(1);
    }

    // ---------- admin proprietor override audit (PAR-315, V26) ----------

    @Test
    void recordAdminOverride_writesAuditRowWithActor() throws Exception {
        UUID from = inviter; // current proprietor from seed()
        UUID to = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        assertThat(requests.recordAdminOverride(firmId, from.toString(), to.toString(),
                actor.toString(), "override-tok-1")).isEqualTo(1);

        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT status, bin_to_uuid(from_uuid_bin), bin_to_uuid(to_uuid_bin), " +
                     "bin_to_uuid(actor_uuid_bin) FROM firm_transfer_requests " +
                     "WHERE firm_id = ? AND status = 'ADMIN_OVERRIDE'")) {
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
        assertThat(requests.recordAdminOverride(firmId, from.toString(), to.toString(),
                actor.toString(), "override-tok-2")).isEqualTo(1);
    }
}
