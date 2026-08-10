package io.paradaux.business.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.InternalException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.business.mappers.FirmRoleMapper;
import io.paradaux.business.mappers.FirmStaffMapper;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmEmployee;
import io.paradaux.business.model.FirmRolePermission;
import io.paradaux.business.model.RolePermission;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Singleton
public class FirmStaffServiceImpl implements FirmStaffService {

    private final FirmService firms;
    private final FirmRoleMapper roles;
    private final FirmStaffMapper staff;
    private final FirmAccountService accounts;

    @Inject
    public FirmStaffServiceImpl(FirmService firms, FirmRoleMapper roles, FirmStaffMapper staff, FirmAccountService accounts) {
        this.firms = firms;
        this.roles = roles;
        this.staff = staff;
        this.accounts = accounts;
    }

    /**
     * Resolves the default (lowest-ranked) role for a firm.
     * Falls back to "Employee" if no roles are configured.
     */
    private String resolveDefaultRole(int firmId) {
        String role = roles.findLowestRole(firmId);
        return role != null ? role : "Employee";
    }

    @Override
    public void fireEmployee(String firmName, UUID playerId, UUID actorId) {
        fireEmployee(firms.getFirmByNameOrId(firmName), playerId, actorId);
    }

    @Override
    public void fireEmployee(int firmId, UUID playerId, UUID actorId) {
        fireEmployee(firms.getFirmById(firmId), playerId, actorId);
    }

    private void fireEmployee(Firm firm, UUID playerId, UUID actorId) {
        if (firm == null) {
            throw new BadCommandException("No such firm");
        }

        if (!hasPermission(firm.getFirmId(), actorId, RolePermission.ADMIN)) {
            throw new NoPermissionException("No permission.");
        }

        if (firms.isProprietor(firm.getFirmId(), playerId)) {
            throw new BadCommandException("You can’t fire the proprietor.");
        }

        if(playerId.equals(actorId)) {
            throw new BadCommandException("You cannot do this to yourself.");
        }

        int rows = staff.endCurrentEmployment(firm.getFirmId(), playerId.toString(), actorId.toString());
        if (rows != 1) throw new InternalException("Failed to end this employment.");

        accounts.syncAllFirmAccounts(firm.getFirmId());
    }

    @Override
    public void hireEmployee(int firmId, UUID playerId, UUID actorId) {
        Firm firm = firms.getFirmById(firmId);
        if (firm == null) throw new BadCommandException("No such firm");

        // Public-API contract: caller must hold ADMIN on the firm. The invite-
        // acceptance flow uses hireEmployeeFromInvite to skip this check.
        if (!hasPermission(firm.getFirmId(), actorId, RolePermission.ADMIN)) {
            throw new NoPermissionException("No permission.");
        }

        hireEmployeeInternal(firm, playerId, actorId);
    }

    @Override
    public void hireEmployeeFromInvite(int firmId, UUID playerId, UUID inviter) {
        Firm firm = firms.getFirmById(firmId);
        if (firm == null) throw new BadCommandException("No such firm");
        // Permission check skipped: the invite row is the artifact of the
        // inviter's INVITE-time permission. See FirmStaffService Javadoc.
        hireEmployeeInternal(firm, playerId, inviter);
    }

    /** Shared body — performs no permission check. */
    private void hireEmployeeInternal(Firm firm, UUID playerId, UUID actorId) {
        if (playerId.equals(actorId)) {
            throw new BadCommandException("You cannot do this to yourself.");
        }
        if (isEmployedBy(firm.getFirmId(), playerId)) {
            throw new BadCommandException("Player is already employed by this firm.");
        }
        String defaultRole = resolveDefaultRole(firm.getFirmId());
        int rows = staff.insertEmployment(
                firm.getFirmId(), playerId.toString(), defaultRole, actorId.toString()
        );
        if (rows != 1) throw new InternalException("Failed to hire employee.");
        accounts.syncAllFirmAccounts(firm.getFirmId());
    }

    @Override
    public String promoteEmployee(String firmName, UUID playerId, UUID actorId) {
        return promoteEmployee(firms.getFirmByNameOrId(firmName), playerId, actorId);
    }

    @Override
    public String promoteEmployee(int firmId, UUID playerId, UUID actorId) {
        return promoteEmployee(firms.getFirmById(firmId), playerId, actorId);
    }

    private String promoteEmployee(Firm firm, UUID playerId, UUID actorId) {
        if (firm == null) {
            throw new BadCommandException("No such firm");
        }

        if(playerId.equals(actorId)) {
            throw new BadCommandException("You cannot do this to yourself.");
        }

        if (!hasPermission(firm.getFirmId(), actorId, RolePermission.ADMIN)) {
            throw new NoPermissionException("No permission.");
        }

        if (!isEmployedBy(firm.getFirmId(), playerId)) {
            throw new BadCommandException("You can't promote someone not employed by this firm.");
        }

        // Current role -> rank
        String currentRole = getCurrentRole(firm.getFirmId(), playerId);
        if (currentRole == null || currentRole.isBlank()) {
            throw new InternalException("Employee has no current role.");
        }

        Integer currentRank = roles.getRank(firm.getFirmId(), currentRole);
        if (currentRank == null) {
            throw new InternalException("Current role is missing from firm_roles.");
        }

        // Find next higher role
        String nextRole = roles.findRoleAbove(firm.getFirmId(), currentRank);
        if (nextRole == null) {
            throw new BadCommandException("They are already at the highest rank.");
        }

        int rows = staff.updateCurrentRole(firm.getFirmId(), playerId.toString(), nextRole);
        if (rows != 1) {
            throw new InternalException("Failed to update role while promoting.");
        }

        accounts.syncAllFirmAccounts(firm.getFirmId());
        return nextRole;
    }

    @Override
    public String demoteEmployee(String firmName, UUID playerId, UUID actorId) {
        return demoteEmployee(firms.getFirmByNameOrId(firmName), playerId, actorId);
    }

    @Override
    public String demoteEmployee(int firmId, UUID playerId, UUID actorId) {
        return demoteEmployee(firms.getFirmById(firmId), playerId, actorId);
    }

    private String demoteEmployee(Firm firm, UUID playerId, UUID actorId) {
        if (firm == null) {
            throw new BadCommandException("No such firm");
        }

        if(playerId.equals(actorId)) {
            throw new BadCommandException("You cannot do this to yourself.");
        }

        // FIX: throw if actor LACKS permission
        if (!hasPermission(firm.getFirmId(), actorId, RolePermission.ADMIN)) {
            throw new NoPermissionException("No permission.");
        }

        if (!isEmployedBy(firm.getFirmId(), playerId)) {
            throw new BadCommandException("You can't demote someone not employed by this firm.");
        }

        String currentRole = getCurrentRole(firm.getFirmId(), playerId);
        if (currentRole == null || currentRole.isBlank()) {
            throw new InternalException("Employee has no current role.");
        }

        Integer currentRank = roles.getRank(firm.getFirmId(), currentRole);
        if (currentRank == null) {
            throw new InternalException("Current role is missing from firm_roles.");
        }

        // Find next lower role
        String nextRole = roles.findRoleBelow(firm.getFirmId(), currentRank);
        if (nextRole == null) {
            throw new BadCommandException("They are already at the lowest rank.");
        }

        int rows = staff.updateCurrentRole(firm.getFirmId(), playerId.toString(), nextRole);
        if (rows != 1) {
            throw new InternalException("Failed to update role while demoting.");
        }

        accounts.syncAllFirmAccounts(firm.getFirmId());
        return nextRole;
    }

    @Override
    public void resignFromFirm(String firmName, UUID playerId) {
        resignFromFirm(firms.getFirmByNameOrId(firmName), playerId);
    }

    @Override
    public void resignFromFirm(int firmId, UUID playerId) {
        resignFromFirm(firms.getFirmById(firmId), playerId);
    }

    private void resignFromFirm(Firm firm, UUID playerId) {
        if (firm == null) {
            throw new BadCommandException("No such firm");
        }

        if (!isEmployedBy(firm.getFirmId(), playerId)) {
            throw new BadCommandException("You are not employed by this firm");
        }

        staff.endCurrentEmployment(firm.getFirmId(), playerId.toString(), playerId.toString());

        accounts.syncAllFirmAccounts(firm.getFirmId());
    }

    @Override
    public List<FirmEmployee> getCurrentEmployees(String firmName) {
        return getCurrentEmployees(firms.getAnyFirmByNameOrId(firmName));
    }

    @Override
    public List<FirmEmployee> getCurrentEmployees(int firmId) {
        return getCurrentEmployees(firms.getAnyFirmById(firmId));
    }

    private List<FirmEmployee> getCurrentEmployees(Firm firm) {
        // Archived-inclusive: listing a roster is a read, so a defunct firm's staff can
        // still be inspected (consistent with /firm info). Mutating staff goes through
        // the active-only paths instead (PAR-87).
        if (firm == null) {
            throw new BadCommandException("No such firm");
        }

        List<FirmEmployee> employees = staff.listCurrentEmployeesByFirm(firm.getFirmId());
        employees.addFirst(getProprietorAsEmployee(firm));

        return employees;
    }

    @Override
    public List<Player> getOnlineEmployees(String firmName) {
        return toOnline(getCurrentEmployees(firmName));
    }

    @Override
    public List<Player> getOnlineEmployees(int firmId) {
        return toOnline(getCurrentEmployees(firmId));
    }

    private static List<Player> toOnline(List<FirmEmployee> employees) {
        return employees.stream()
                .map(e -> Bukkit.getPlayer(UUID.fromString(e.getPlayerUuid())))
                .filter(Objects::nonNull)
                .toList();
    }

    public boolean hasPermission(Integer firmId, UUID playerId, RolePermission permission) {
        if (firms.isProprietor(firmId, playerId)) return true;
        if (!isEmployedBy(firmId, playerId)) return false;

        String roleName = getCurrentRole(firmId, playerId);
        if (roleName == null || roleName.isBlank()) return false;

        List<FirmRolePermission> perms = roles.listPermissionsByRole(firmId, roleName.trim());

        for (FirmRolePermission rp : perms) {
            if (rp.getPermission() == permission) return true;
        }
        return false;
    }


    @Override
    public boolean isEmployedBy(Integer firmId, UUID playerId) {
        return staff.isEmployedBy(firmId, playerId.toString());
    }

    @Override
    public String getCurrentRole(Integer firmId, UUID playerId) {
        return staff.getCurrentRole(firmId, playerId.toString());
    }

    @Override
    public FirmEmployee getProprietorAsEmployee(String firmName) {
        return getProprietorAsEmployee(firms.getAnyFirmByNameOrId(firmName));
    }

    @Override
    public FirmEmployee getProprietorAsEmployee(int firmId) {
        return getProprietorAsEmployee(firms.getAnyFirmById(firmId));
    }

    private FirmEmployee getProprietorAsEmployee(Firm firm) {
        // Archived-inclusive read; guard against an unknown firm rather than NPE'ing.
        if (firm == null) {
            throw new BadCommandException("No such firm");
        }
        String proprietorRoleName = roles.findProprietorRoleName(firm.getFirmId());
        if (proprietorRoleName == null) {
            proprietorRoleName = "Proprietor";
        }
        return new FirmEmployee(firm.getFirmId(), firm.getProprietorUuid(), proprietorRoleName, firm.getCreatedAt(), null, firm.getProprietorUuid(), null, true);
    }
}
