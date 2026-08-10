package io.paradaux.business.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;

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
import org.mybatis.guice.transactional.Transactional;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.util.UUID;

import static io.paradaux.hibernia.framework.utils.StringUtils.random32;

@Singleton
public class FirmRequestServiceImpl implements FirmRequestService {

    private static final int OFFER_EXPIRATION_MINUTES = 5;
    private final FirmRequestMapper requests;
    private final FirmService firms;
    private final FirmStaffService staff;
    private final FirmAccountService accounts;

    @Inject
    public FirmRequestServiceImpl(FirmRequestMapper requests, FirmService firms, FirmStaffService staff,
                                  FirmAccountService accounts) {
        this.requests = requests;
        this.firms = firms;
        this.staff = staff;
        this.accounts = accounts;
    }

    @Override
    public ExpiryResult expireStale() {
        int transfers = requests.expireStaleTransfers();
        int invites = requests.expireStaleInvites();
        return new ExpiryResult(transfers, invites);
    }

    @Override
    public void offerEmployment(String firmName, UUID targetId, UUID actorId) {
        offerEmployment(firms.getFirmByNameOrId(firmName), targetId, actorId);
    }

    @Override
    public void offerEmployment(int firmId, UUID targetId, UUID actorId) {
        offerEmployment(firms.getFirmById(firmId), targetId, actorId);
    }

    private void offerEmployment(Firm firm, UUID targetId, UUID actorId) {
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(OFFER_EXPIRATION_MINUTES);

        if (firm == null) {
            throw new NotFoundException("Firm not found.");
        }

        if (!staff.hasPermission(firm.getFirmId(), actorId, RolePermission.ADMIN)) {
            throw new NoPermissionException("You do not have permission to do this.");
        }

        if (staff.isEmployedBy(firm.getFirmId(), targetId)) {
            throw new BadCommandException("This user is already employed.");
        }

        if(targetId.equals(actorId)) {
            throw new BadCommandException("You cannot do this to yourself.");
        }

        if (requests.hasPendingJobOffer(firm.getFirmId(), targetId.toString())) {
            throw new BadCommandException("This player already has a pending invite to " + firm.getDisplayName()
                    + ". Wait for them to accept it, or rescind it first with /firm offer rescind "
                    + firm.getDisplayName() + " <player>.");
        }

        int rowsAffected = requests.createInvite(firm.getFirmId(), targetId.toString(), actorId.toString(), expiresAt);

        if (rowsAffected != 1) {
            throw new InternalException("Internal exception occurred while trying to offer employment.");
        }
    }

    @Override
    public void rescindEmploymentOffer(String firmName, UUID playerId, UUID actorId) {
        rescindEmploymentOffer(firms.getFirmByNameOrId(firmName), playerId, actorId);
    }

    @Override
    public void rescindEmploymentOffer(int firmId, UUID playerId, UUID actorId) {
        rescindEmploymentOffer(firms.getFirmById(firmId), playerId, actorId);
    }

    private void rescindEmploymentOffer(Firm firm, UUID playerId, UUID actorId) {
        if (firm == null) {
            throw new NotFoundException("Firm not found.");
        }

        if (!staff.hasPermission(firm.getFirmId(), actorId, RolePermission.ADMIN)) {
            throw new NoPermissionException("You do not have permission to do this.");
        }

        if (staff.isEmployedBy(firm.getFirmId(), playerId)) {
            throw new BadCommandException("This user is already employed.");
        }

        if(playerId.equals(actorId)) {
            throw new BadCommandException("You cannot do this to yourself.");
        }

        if (!requests.hasPendingJobOffer(firm.getFirmId(), playerId.toString())) {
            throw new BadCommandException("This user does not have a pending offer.");
        }

        int rows = requests.rescindInvite(firm.getFirmId(), playerId.toString());
        if (rows != 1) {
            throw new InternalException("Failed to rescind invite, was there a pending invite to begin with?");
        }
    }

    @Override
    public void acceptEmploymentOffer(String firmName, UUID playerId, UUID actorId) {
        acceptEmploymentOffer(firms.getFirmByNameOrId(firmName), playerId, actorId);
    }

    @Override
    public void acceptEmploymentOffer(int firmId, UUID playerId, UUID actorId) {
        acceptEmploymentOffer(firms.getFirmById(firmId), playerId, actorId);
    }

    private void acceptEmploymentOffer(Firm firm, UUID playerId, UUID actorId) {
        if (firm == null) {
            throw new NotFoundException("Firm not found.");
        }

        // Only the offer's target may accept it. Without this, the public API
        // (RequestApi.acceptOffer) would let any caller accept a pending offer
        // on another player's behalf (ADT-34).
        if (!actorId.equals(playerId)) {
            throw new NoPermissionException("You can only accept your own employment offer.");
        }

        if (!requests.hasPendingJobOffer(firm.getFirmId(), playerId.toString())) {
            throw new BadCommandException("This user does not have a pending offer.");
        }

        // Lock the invite row
        String inviterStr = requests.lockPendingInviter(firm.getFirmId(), playerId.toString());
        if (inviterStr == null) {
            throw new BadCommandException("No valid invite to accept.");
        }
        UUID inviter = UUID.fromString(inviterStr);

        // The offer is contingent on the inviter still holding hiring authority
        // (ADT-70): re-check that the inviter still has ADMIN — the same permission
        // offerEmployment required. An admin who was demoted, removed, or left the
        // firm after sending the invite can no longer hire, so the offer is no
        // longer valid. We leave the row pending (don't reject) so it becomes
        // acceptable again if a valid admin's authority is restored, or expires.
        if (!staff.hasPermission(firm.getFirmId(), inviter, RolePermission.ADMIN)) {
            throw new BadCommandException(
                    "This offer is no longer valid — the person who invited you no longer has "
                    + "permission to hire for this firm.");
        }

        // Flip status
        int updated = requests.acceptInvite(firm.getFirmId(), playerId.toString());
        if (updated != 1) {
            throw new InternalException("Failed to accept invite after lock.");
        }

        staff.hireEmployeeFromInvite(firm.getFirmId(), playerId, inviter);
    }

    @Override
    public void rejectEmploymentOffer(String firmName, UUID playerId, UUID actorId) {
        rejectEmploymentOffer(firms.getFirmByNameOrId(firmName), playerId, actorId);
    }

    @Override
    public void rejectEmploymentOffer(int firmId, UUID playerId, UUID actorId) {
        rejectEmploymentOffer(firms.getFirmById(firmId), playerId, actorId);
    }

    private void rejectEmploymentOffer(Firm firm, UUID playerId, UUID actorId) {
        if (firm == null) {
            throw new NotFoundException("Firm not found.");
        }

        // Only the offer's target may reject it (ADT-34) — mirrors accept.
        if (!actorId.equals(playerId)) {
            throw new NoPermissionException("You can only reject your own employment offer.");
        }

        if (!requests.hasPendingJobOffer(firm.getFirmId(), playerId.toString())) {
            throw new BadCommandException("This user does not have a pending offer.");
        }

        int rows = requests.rejectInvite(firm.getFirmId(), playerId.toString());
        if (rows != 1) {
            throw new InternalException("Failed to reject invite (not pending or already expired).");
        }
    }

    @Override
    public String beginTransferProprietorship(String firmName, UUID newProprietorId, UUID actorId) {
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            throw new NotFoundException("Firm not found.");
        }

        // Only the current proprietor may initiate a transfer of their firm.
        // Without this any holder of business.transfer.begin could spawn
        // transfer requests against firms they don't own.
        if (!firm.getProprietorUuid().equalsIgnoreCase(actorId.toString())) {
            throw new NoPermissionException("Only the current proprietor can transfer a firm.");
        }

        if(newProprietorId.equals(actorId)) {
            throw new BadCommandException("You cannot do this to yourself.");
        }

        String code = random32();
        try {
            requests.createTransferRequest(firm.getFirmId(), newProprietorId.toString(), code ,LocalDateTime.now().plusMinutes(OFFER_EXPIRATION_MINUTES));
        } catch (PersistenceException ex) {
            if (ex.getCause() instanceof SQLIntegrityConstraintViolationException) {
                throw new BadCommandException("You already have a pending transfer request. Cancel the previous or wait for it to be approved.");
            }
            // Any other persistence failure means the request was NOT stored —
            // don't return a code for a transfer that doesn't exist (ADT-56).
            throw new InternalException("Failed to create the transfer request. Please try again.");
        }
        return code;
    }

    @Override
    public boolean confirmTransferProprietorship(String firmName, UUID newProprietorId, String code, UUID actorId) {
        Firm firm = firms.getFirmByNameOrId(firmName);

        if (firm == null) {
            throw new NotFoundException("Firm not found.");
        }

        // Only the current proprietor can confirm the transfer they began.
        // The token is a soft secret, but defense-in-depth keeps anyone with
        // the `business.transfer.confirm` permission and a leaked code from
        // hijacking ownership.
        if (!firm.getProprietorUuid().equalsIgnoreCase(actorId.toString())) {
            throw new NoPermissionException("Only the current proprietor can confirm a transfer.");
        }

        int rows = requests.confirmTransfer(firm.getFirmId(), newProprietorId.toString(), code);
        return rows > 0;
    }

    @Override
    public void cancelTransferProprietorship(String firmName, UUID newProprietorId, UUID actorId) {
        Firm firm = firms.getFirmByNameOrId(firmName);

        if (firm == null) {
            throw new NotFoundException("Firm not found.");
        }

        // Only the proprietor who began the transfer may cancel it. Without this,
        // anyone with the cancel permission could tear down a legitimate pending
        // ownership transfer for a firm they don't own (griefing) (ADT-34).
        if (!firm.getProprietorUuid().equalsIgnoreCase(actorId.toString())) {
            throw new NoPermissionException("Only the current proprietor can cancel a transfer.");
        }

        requests.rejectTransfer(firm.getFirmId(), newProprietorId.toString());
    }

    /**
     * @return the <em>previous</em> proprietor (captured before the handover) so
     *         callers can notify the outgoing owner. Read up front rather than
     *         from the now-stale local firm after the update (ADT-56).
     */
    // Atomic: the proprietor handover and the treasury-account reassignment must
    // commit or roll back together. Before this was transactional, a failure in
    // reassignAccountsToNewProprietor (e.g. a stale firm_accounts row whose
    // account no longer resolves in Treasury) left the just-committed proprietor
    // change in place with the account owner/authorizers still on the previous
    // owner — the new proprietor was locked out of the firm's money. Wrapping the
    // method rolls the proprietor change back on any such failure so the transfer
    // fails cleanly and can be retried, never stranding the account (PAR-141).
    // (Treasury writes are cross-plugin IPC and can't enrol in this JDBC
    // transaction, but the common failure — the first reassignOwner throwing —
    // occurs before any Treasury write, so rollback is clean.)
    @Transactional
    @Override
    public UUID completeTransferProprietorship(String firmName, UUID newProprietorId) {
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            throw new NotFoundException("Firm not found.");
        }

        // Captured before updateProprietor so the returned value is unambiguous.
        UUID previousProprietor = UUID.fromString(firm.getProprietorUuid());

        // Gate on a CONFIRMED transfer: acceptTransfer only flips a CONFIRMED,
        // non-expired request to ACCEPTED and returns 1. If there is none for
        // this new proprietor, refuse — otherwise updateProprietor would hand
        // the firm over with no began/confirmed transfer at all (firm theft).
        if (requests.acceptTransfer(firm.getFirmId(), newProprietorId.toString()) != 1) {
            throw new BadCommandException(
                    "No confirmed proprietorship transfer to accept for this firm.");
        }

        if (staff.isEmployedBy(firm.getFirmId(), newProprietorId)) {
            staff.resignFromFirm(firmName, newProprietorId);
        }

        firms.updateProprietor(firm.getFirmId(), newProprietorId);

        // Reflect the ownership change in Treasury: hand the firm's accounts to
        // the new proprietor and re-sync access. Done after updateProprietor so
        // the sync derives the new proprietor as owner. Without this the new
        // owner is locked out of the firm's accounts and the previous owner
        // keeps owner-level access (PAR-141).
        accounts.reassignAccountsToNewProprietor(firm.getFirmId(), newProprietorId);

        return previousProprietor;
    }

    /**
     * @return the current proprietor (unchanged — a rejection performs no
     *         handover) so callers can notify them their transfer was declined.
     */
    @Override
    public UUID rejectTransferProprietorship(String firmName, UUID newProprietorId, UUID actorId) {
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            throw new NotFoundException("Firm not found.");
        }

        // Only the prospective new proprietor may decline the transfer addressed
        // to them. Without this, anyone could reject a pending transfer on the
        // target's behalf (ADT-34).
        if (!actorId.equals(newProprietorId)) {
            throw new NoPermissionException("You can only reject a transfer addressed to you.");
        }

        requests.rejectTransfer(firm.getFirmId(), newProprietorId.toString());
        return UUID.fromString(firm.getProprietorUuid());
    }
}
