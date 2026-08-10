package io.paradaux.business.services.impl;

import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.InternalException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.business.mappers.FirmRequestMapper;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.RolePermission;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmRequestService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirmRequestServiceImplTest {

    @Mock FirmRequestMapper requests;
    @Mock FirmService firms;
    @Mock FirmStaffService staff;
    @Mock FirmAccountService accounts;

    private FirmRequestServiceImpl svc;

    private final UUID actor = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        svc = new FirmRequestServiceImpl(requests, firms, staff, accounts);
    }

    private Firm firm() {
        Firm f = new Firm();
        f.setFirmId(1);
        f.setProprietorUuid(UUID.randomUUID().toString());
        return f;
    }

    private Firm firmOwnedBy(UUID owner) {
        Firm f = firm();
        f.setProprietorUuid(owner.toString());
        return f;
    }

    // ---------- expireStale (plugin-architecture/0005) ----------

    @Test
    void expireStale_delegatesToMapperAndTallies() {
        when(requests.expireStaleTransfers()).thenReturn(3);
        when(requests.expireStaleInvites()).thenReturn(2);

        FirmRequestService.ExpiryResult result = svc.expireStale();

        assertThat(result.transfers()).isEqualTo(3);
        assertThat(result.invites()).isEqualTo(2);
        verify(requests).expireStaleTransfers();
        verify(requests).expireStaleInvites();
    }

    // ---------- offerEmployment ----------

    @Test
    void offerEmployment_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.offerEmployment("Ghost", target, actor))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void offerEmployment_requiresAdminPermission() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(false);
        assertThatThrownBy(() -> svc.offerEmployment("Acme", target, actor))
                .isInstanceOf(NoPermissionException.class);
    }

    @Test
    void offerEmployment_alreadyEmployed_throws() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(staff.isEmployedBy(1, target)).thenReturn(true);
        assertThatThrownBy(() -> svc.offerEmployment("Acme", target, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void offerEmployment_cannotOfferSelf() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(staff.isEmployedBy(1, actor)).thenReturn(false);
        assertThatThrownBy(() -> svc.offerEmployment("Acme", actor, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void offerEmployment_alreadyPending_throws() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(staff.isEmployedBy(1, target)).thenReturn(false);
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(true);
        assertThatThrownBy(() -> svc.offerEmployment("Acme", target, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void offerEmployment_succeeds() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(staff.isEmployedBy(1, target)).thenReturn(false);
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(false);
        when(requests.createInvite(eq(1), eq(target.toString()), eq(actor.toString()), any(LocalDateTime.class))).thenReturn(1);

        svc.offerEmployment("Acme", target, actor);
        verify(requests).createInvite(eq(1), eq(target.toString()), eq(actor.toString()), any(LocalDateTime.class));
    }

    @Test
    void offerEmployment_zeroRowsThrowsInternal() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(staff.isEmployedBy(1, target)).thenReturn(false);
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(false);
        when(requests.createInvite(anyInt(), anyString(), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> svc.offerEmployment("Acme", target, actor))
                .isInstanceOf(InternalException.class);
    }

    // ---------- rescindEmploymentOffer ----------

    @Test
    void rescindEmploymentOffer_succeeds() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(staff.isEmployedBy(1, target)).thenReturn(false);
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(true);
        when(requests.rescindInvite(1, target.toString())).thenReturn(1);

        svc.rescindEmploymentOffer("Acme", target, actor);
        verify(requests).rescindInvite(1, target.toString());
    }

    @Test
    void rescindEmploymentOffer_zeroRowsThrowsInternal() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(staff.isEmployedBy(1, target)).thenReturn(false);
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(true);
        when(requests.rescindInvite(1, target.toString())).thenReturn(0);

        assertThatThrownBy(() -> svc.rescindEmploymentOffer("Acme", target, actor))
                .isInstanceOf(InternalException.class);
    }

    @Test
    void rescindEmploymentOffer_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.rescindEmploymentOffer("Ghost", target, actor))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void rescindEmploymentOffer_alreadyEmployedThrows() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(staff.isEmployedBy(1, target)).thenReturn(true);
        assertThatThrownBy(() -> svc.rescindEmploymentOffer("Acme", target, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void rescindEmploymentOffer_cannotTargetSelf() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(staff.isEmployedBy(1, actor)).thenReturn(false);
        assertThatThrownBy(() -> svc.rescindEmploymentOffer("Acme", actor, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void rescindEmploymentOffer_noPendingThrows() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(staff.isEmployedBy(1, target)).thenReturn(false);
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(false);
        assertThatThrownBy(() -> svc.rescindEmploymentOffer("Acme", target, actor))
                .isInstanceOf(BadCommandException.class);
    }

    // ---------- accept / reject ----------

    @Test
    void acceptEmploymentOffer_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.acceptEmploymentOffer("Ghost", target, target))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void acceptEmploymentOffer_wrongActor_throwsNoPermission() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        assertThatThrownBy(() -> svc.acceptEmploymentOffer("Acme", target, actor))
                .isInstanceOf(NoPermissionException.class);
        verify(requests, never()).acceptInvite(anyInt(), anyString());
    }

    @Test
    void acceptEmploymentOffer_noPendingThrows() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(false);
        assertThatThrownBy(() -> svc.acceptEmploymentOffer("Acme", target, target))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void acceptEmploymentOffer_lockedNullThrows() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(true);
        when(requests.lockPendingInviter(1, target.toString())).thenReturn(null);
        assertThatThrownBy(() -> svc.acceptEmploymentOffer("Acme", target, target))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void acceptEmploymentOffer_zeroAcceptsThrowsInternal() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(true);
        when(requests.lockPendingInviter(1, target.toString())).thenReturn(actor.toString());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(requests.acceptInvite(1, target.toString())).thenReturn(0);
        assertThatThrownBy(() -> svc.acceptEmploymentOffer("Acme", target, target))
                .isInstanceOf(InternalException.class);
        verify(staff, never()).hireEmployee(anyInt(), any(), any());
    }

    @Test
    void acceptEmploymentOffer_hiresOnSuccess() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(true);
        when(requests.lockPendingInviter(1, target.toString())).thenReturn(actor.toString());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(requests.acceptInvite(1, target.toString())).thenReturn(1);

        svc.acceptEmploymentOffer("Acme", target, target);
        verify(staff).hireEmployeeFromInvite(1, target, actor);
    }

    @Test
    void acceptEmploymentOffer_inviterLostPermission_voidsOffer() {
        // ADT-70: the inviter was demoted/removed after sending the invite, so it
        // can no longer be accepted — and nothing is hired.
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(true);
        when(requests.lockPendingInviter(1, target.toString())).thenReturn(actor.toString());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(false);

        assertThatThrownBy(() -> svc.acceptEmploymentOffer("Acme", target, target))
                .isInstanceOf(BadCommandException.class)
                .hasMessageContaining("no longer");
        verify(requests, never()).acceptInvite(anyInt(), any());
        verify(staff, never()).hireEmployeeFromInvite(anyInt(), any(), any());
    }

    @Test
    void rejectEmploymentOffer_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.rejectEmploymentOffer("Ghost", target, target))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void rejectEmploymentOffer_wrongActor_throwsNoPermission() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        assertThatThrownBy(() -> svc.rejectEmploymentOffer("Acme", target, actor))
                .isInstanceOf(NoPermissionException.class);
        verify(requests, never()).rejectInvite(anyInt(), anyString());
    }

    @Test
    void rejectEmploymentOffer_noPendingThrows() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(false);
        assertThatThrownBy(() -> svc.rejectEmploymentOffer("Acme", target, target))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void rejectEmploymentOffer_zeroRowsThrowsInternal() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(true);
        when(requests.rejectInvite(1, target.toString())).thenReturn(0);
        assertThatThrownBy(() -> svc.rejectEmploymentOffer("Acme", target, target))
                .isInstanceOf(InternalException.class);
    }

    @Test
    void rejectEmploymentOffer_succeeds() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(true);
        when(requests.rejectInvite(1, target.toString())).thenReturn(1);
        svc.rejectEmploymentOffer("Acme", target, target);
        verify(requests).rejectInvite(1, target.toString());
    }

    // ---------- transfer proprietorship ----------

    @Test
    void beginTransferProprietorship_cannotTargetSelf() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firmOwnedBy(actor));
        assertThatThrownBy(() -> svc.beginTransferProprietorship("Acme", actor, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void beginTransferProprietorship_returnsCode() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firmOwnedBy(actor));
        String code = svc.beginTransferProprietorship("Acme", target, actor);
        assertThat(code).isNotBlank();
        verify(requests).createTransferRequest(eq(1), eq(target.toString()), eq(code), any());
    }

    @Test
    void beginTransferProprietorship_duplicateGetsFriendlyError() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firmOwnedBy(actor));
        PersistenceException pex = new PersistenceException(new SQLIntegrityConstraintViolationException("dup"));
        when(requests.createTransferRequest(anyInt(), anyString(), anyString(), any())).thenThrow(pex);
        assertThatThrownBy(() -> svc.beginTransferProprietorship("Acme", target, actor))
                .isInstanceOf(BadCommandException.class);
    }

    @Test
    void beginTransferProprietorship_nonConstraintFailure_throwsInternal() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firmOwnedBy(actor));
        // A persistence failure that isn't a constraint violation means the row
        // was NOT stored — must not hand back a code (ADT-56).
        PersistenceException pex = new PersistenceException(new RuntimeException("db gone"));
        when(requests.createTransferRequest(anyInt(), anyString(), anyString(), any())).thenThrow(pex);
        assertThatThrownBy(() -> svc.beginTransferProprietorship("Acme", target, actor))
                .isInstanceOf(InternalException.class);
    }

    @Test
    void beginTransferProprietorship_nonProprietor_throws() {
        // firm() has a random proprietor, so `actor` is not the owner.
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        assertThatThrownBy(() -> svc.beginTransferProprietorship("Acme", target, actor))
                .isInstanceOf(io.paradaux.hibernia.framework.exceptions.NoPermissionException.class);
        verify(requests, never()).createTransferRequest(anyInt(), anyString(), anyString(), any());
    }

    @Test
    void confirmTransferProprietorship_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.confirmTransferProprietorship("Ghost", target, "code", actor))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void confirmTransferProprietorship_nonProprietor_throws() {
        Firm f = firm(); // proprietor is some random UUID
        when(firms.getFirmByNameOrId("Acme")).thenReturn(f);
        assertThatThrownBy(() -> svc.confirmTransferProprietorship("Acme", target, "code", actor))
                .isInstanceOf(io.paradaux.hibernia.framework.exceptions.NoPermissionException.class);
    }

    @Test
    void confirmTransferProprietorship_returnsTrueIfRowsAffected() {
        Firm f = firm();
        UUID owner = UUID.fromString(f.getProprietorUuid());
        when(firms.getFirmByNameOrId("Acme")).thenReturn(f);
        when(requests.confirmTransfer(1, target.toString(), "code")).thenReturn(1);
        assertThat(svc.confirmTransferProprietorship("Acme", target, "code", owner)).isTrue();
    }

    @Test
    void confirmTransferProprietorship_returnsFalseIfNoRows() {
        Firm f = firm();
        UUID owner = UUID.fromString(f.getProprietorUuid());
        when(firms.getFirmByNameOrId("Acme")).thenReturn(f);
        when(requests.confirmTransfer(1, target.toString(), "code")).thenReturn(0);
        assertThat(svc.confirmTransferProprietorship("Acme", target, "code", owner)).isFalse();
    }

    @Test
    void cancelTransferProprietorship_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.cancelTransferProprietorship("Ghost", target, actor))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void cancelTransferProprietorship_nonProprietor_throwsNoPermission() {
        // firm() has a random proprietor, so `actor` is not the owner.
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        assertThatThrownBy(() -> svc.cancelTransferProprietorship("Acme", target, actor))
                .isInstanceOf(NoPermissionException.class);
        verify(requests, never()).rejectTransfer(anyInt(), anyString());
    }

    @Test
    void cancelTransferProprietorship_callsReject() {
        // Only the proprietor may cancel (ADT-34): the actor must own the firm.
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firmOwnedBy(actor));
        svc.cancelTransferProprietorship("Acme", target, actor);
        verify(requests).rejectTransfer(1, target.toString());
    }

    @Test
    void completeTransferProprietorship_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.completeTransferProprietorship("Ghost", target))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void completeTransferProprietorship_resignsIfNewProprietorEmployed() {
        Firm f = firm();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(f);
        when(requests.acceptTransfer(1, target.toString())).thenReturn(1);
        when(staff.isEmployedBy(1, target)).thenReturn(true);

        UUID old = svc.completeTransferProprietorship("Acme", target);
        assertThat(old).isEqualTo(UUID.fromString(f.getProprietorUuid()));

        verify(staff).resignFromFirm("Acme", target);
        verify(requests).acceptTransfer(1, target.toString());
        verify(firms).updateProprietor(1, target);
        // Treasury access must be reconciled to the new proprietor (PAR-141).
        verify(accounts).reassignAccountsToNewProprietor(1, target);
    }

    @Test
    void completeTransferProprietorship_skipsResignWhenNotEmployed() {
        Firm f = firm();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(f);
        when(requests.acceptTransfer(1, target.toString())).thenReturn(1);
        when(staff.isEmployedBy(1, target)).thenReturn(false);

        svc.completeTransferProprietorship("Acme", target);
        verify(staff, never()).resignFromFirm(any(), any());
    }

    @Test
    void completeTransferProprietorship_noConfirmedRequest_throws() {
        // acceptTransfer returns 0 (no CONFIRMED, non-expired request) → refuse
        // and never hand over the firm. This is the firm-theft guard.
        Firm f = firm();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(f);
        when(requests.acceptTransfer(1, target.toString())).thenReturn(0);

        assertThatThrownBy(() -> svc.completeTransferProprietorship("Acme", target))
                .isInstanceOf(BadCommandException.class);
        verify(firms, never()).updateProprietor(anyInt(), any());
        verify(staff, never()).resignFromFirm(any(), any());
        verify(accounts, never()).reassignAccountsToNewProprietor(anyInt(), any());
    }

    @Test
    void rejectTransferProprietorship_unknownFirm_throws() {
        when(firms.getFirmByNameOrId("Ghost")).thenReturn(null);
        assertThatThrownBy(() -> svc.rejectTransferProprietorship("Ghost", target, target))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void rejectTransferProprietorship_wrongActor_throwsNoPermission() {
        when(firms.getFirmByNameOrId("Acme")).thenReturn(firm());
        // actor is not the transfer's target (newProprietorId == target).
        assertThatThrownBy(() -> svc.rejectTransferProprietorship("Acme", target, actor))
                .isInstanceOf(NoPermissionException.class);
        verify(requests, never()).rejectTransfer(anyInt(), anyString());
    }

    @Test
    void rejectTransferProprietorship_returnsCurrentProprietor() {
        Firm f = firm();
        when(firms.getFirmByNameOrId("Acme")).thenReturn(f);
        UUID returned = svc.rejectTransferProprietorship("Acme", target, target);
        assertThat(returned).isEqualTo(UUID.fromString(f.getProprietorUuid()));
        verify(requests).rejectTransfer(1, target.toString());
    }

    // ---------- int-id overloads (structure/0004) ----------
    // Same behaviour as the String overloads, resolving the firm by id
    // (getFirmById) rather than round-tripping through getFirmByNameOrId.

    @Test
    void offerEmployment_byId_succeeds() {
        when(firms.getFirmById(1)).thenReturn(firm());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(staff.isEmployedBy(1, target)).thenReturn(false);
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(false);
        when(requests.createInvite(eq(1), eq(target.toString()), eq(actor.toString()), any(LocalDateTime.class))).thenReturn(1);

        svc.offerEmployment(1, target, actor);

        verify(requests).createInvite(eq(1), eq(target.toString()), eq(actor.toString()), any(LocalDateTime.class));
    }

    @Test
    void rescindEmploymentOffer_byId_succeeds() {
        when(firms.getFirmById(1)).thenReturn(firm());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(staff.isEmployedBy(1, target)).thenReturn(false);
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(true);
        when(requests.rescindInvite(1, target.toString())).thenReturn(1);

        svc.rescindEmploymentOffer(1, target, actor);

        verify(requests).rescindInvite(1, target.toString());
    }

    @Test
    void acceptEmploymentOffer_byId_hiresOnSuccess() {
        when(firms.getFirmById(1)).thenReturn(firm());
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(true);
        when(requests.lockPendingInviter(1, target.toString())).thenReturn(actor.toString());
        when(staff.hasPermission(1, actor, RolePermission.ADMIN)).thenReturn(true);
        when(requests.acceptInvite(1, target.toString())).thenReturn(1);

        svc.acceptEmploymentOffer(1, target, target);

        verify(staff).hireEmployeeFromInvite(1, target, actor);
    }

    @Test
    void rejectEmploymentOffer_byId_succeeds() {
        when(firms.getFirmById(1)).thenReturn(firm());
        when(requests.hasPendingJobOffer(1, target.toString())).thenReturn(true);
        when(requests.rejectInvite(1, target.toString())).thenReturn(1);

        svc.rejectEmploymentOffer(1, target, target);

        verify(requests).rejectInvite(1, target.toString());
    }
}
