package io.paradaux.business.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.exceptions.ConflictException;
import io.paradaux.hibernia.framework.exceptions.NoPermissionException;
import io.paradaux.hibernia.framework.exceptions.NotFoundException;
import io.paradaux.business.mappers.FirmRoleMapper;
import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmRole;
import io.paradaux.business.model.FirmRolePermission;
import io.paradaux.business.model.RolePermission;
import io.paradaux.business.services.FirmAccountService;
import io.paradaux.business.services.FirmRoleService;
import io.paradaux.business.services.FirmService;
import io.paradaux.business.services.FirmStaffService;
import org.apache.ibatis.exceptions.PersistenceException;
import org.mybatis.guice.transactional.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Singleton
public class FirmRoleServiceImpl implements FirmRoleService {

    private final FirmService firms;
    private final FirmStaffService staff;
    private final FirmRoleMapper roles;
    private final FirmAccountService accounts;

    @Inject
    public FirmRoleServiceImpl(FirmService firms, FirmStaffService staff, FirmRoleMapper roles,
                               FirmAccountService accounts) {
        this.firms = firms;
        this.staff = staff;
        this.roles = roles;
        this.accounts = accounts;
    }

    // @Transactional so the role insert and its baseline DEFAULT-permission
    // grant are atomic: previously these were two separate, non-atomic command
    // calls and a failure between them left a role with no permissions (ADT-56).
    // @Transactional on both public entrypoints (not the shared private body):
    // mybatis-guice intercepts only proxied calls, so the transaction boundary
    // must sit on the method the proxy dispatches — a this-call into the helper
    // would not be intercepted and would lose atomicity (ADT-56).
    @Transactional
    @Override
    public void createRole(String firmName, String roleName, int rankOrder, UUID actorId) {
        createRoleInternal(firms.getFirmByNameOrId(firmName), roleName, rankOrder, actorId);
    }

    @Transactional
    @Override
    public void createRole(int firmId, String roleName, int rankOrder, UUID actorId) {
        createRoleInternal(firms.getFirmById(firmId), roleName, rankOrder, actorId);
    }

    private void createRoleInternal(Firm firm, String roleName, int rankOrder, UUID actorId) {
        if (firm == null) {
            throw new NotFoundException("Firm not found.");
        }

        if (!staff.hasPermission(firm.getFirmId(), actorId, RolePermission.ADMIN)) {
            throw new NoPermissionException("You don't have permission to manage roles for " + firm.getDisplayName() + ".");
        }

        // Prevent creating a role at or above the proprietor role's rank
        Integer minRank = roles.findMinRankOrder(firm.getFirmId());
        if (minRank != null && rankOrder <= minRank) {
            throw new BadCommandException("Cannot create a role at or above the proprietor role's rank.");
        }

        String normalizedName = normalizeRoleName(roleName);
        FirmRole role = new FirmRole(firm.getFirmId(), normalizedName, rankOrder);

        try {
            int inserted = roles.insertRole(role);
            if (inserted != 1) throw new IllegalStateException("Insert failed for role: " + roleName);
            // Grant the baseline DEFAULT permission in the same transaction so a
            // role is never persisted without it.
            roles.addRolePermission(new FirmRolePermission(firm.getFirmId(), normalizedName, RolePermission.DEFAULT));
        } catch (PersistenceException e) {
            // e.g., duplicate name or uq_role_rank clash
            throw new ConflictException("Could not create role (name or rank already used in this firm).", e);
        }
    }

    @Override
    public void deleteRole(String firmName, String roleName, UUID actorId) {
        deleteRoleInternal(firms.getFirmByNameOrId(firmName), roleName, actorId);
    }

    @Override
    public void deleteRole(int firmId, String roleName, UUID actorId) {
        deleteRoleInternal(firms.getFirmById(firmId), roleName, actorId);
    }

    private void deleteRoleInternal(Firm firm, String roleName, UUID actorId) {
        if (firm == null) {
            throw new NotFoundException("Firm not found.");
        }

        if (!staff.hasPermission(firm.getFirmId(), actorId, RolePermission.ADMIN)) {
            throw new NoPermissionException("You don't have permission to manage roles for " + firm.getDisplayName() + ".");
        }

        // Prevent deleting the proprietor role (the role with the lowest rank_order)
        String proprietorRoleName = roles.findProprietorRoleName(firm.getFirmId());
        if (proprietorRoleName != null && proprietorRoleName.equalsIgnoreCase(normalizeRoleName(roleName))) {
            throw new BadCommandException("The proprietor role cannot be deleted.");
        }

        int deleted = roles.deleteRole(firm.getFirmId(), normalizeRoleName(roleName));
        if (deleted == 0) throw new NotFoundException("Role not found: " + roleName);

        // Role membership feeds Treasury access — reconcile holders now (PAR-141).
        accounts.syncAllFirmAccounts(firm.getFirmId());
    }

    public void renameRole(String firmName, String oldName, String newName, UUID actorId) {
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            throw new NotFoundException("Firm not found: " + firmName);
        }

        if (!staff.hasPermission(firm.getFirmId(), actorId, RolePermission.ADMIN)) {
            throw new NoPermissionException("You don't have permission to manage roles for " + firmName + ".");
        }

        String normalizedOld = normalizeRoleName(oldName);
        String normalizedNew = normalizeRoleName(newName);

        boolean roleExists = roles.listRolesByFirm(firm.getFirmId()).stream()
                .anyMatch(r -> r.getRoleName().equalsIgnoreCase(normalizedOld));
        if (!roleExists) {
            throw new NotFoundException("Role not found: " + oldName);
        }

        try {
            int updated = roles.renameRole(firm.getFirmId(), normalizedOld, normalizedNew);
            if (updated != 1) throw new IllegalStateException("Rename failed for role: " + oldName);
        } catch (PersistenceException e) {
            throw new ConflictException("A role with that name already exists in this firm.", e);
        }

        // Employees resolve permissions by role name — reconcile access (PAR-141).
        accounts.syncAllFirmAccounts(firm.getFirmId());
    }

    @Override
    public void addRolePermission(String firmName, String roleName, String permission, UUID actorId) {
        addRolePermissionInternal(firms.getFirmByNameOrId(firmName), roleName, permission, actorId);
    }

    @Override
    public void addRolePermission(int firmId, String roleName, String permission, UUID actorId) {
        addRolePermissionInternal(firms.getFirmById(firmId), roleName, permission, actorId);
    }

    private void addRolePermissionInternal(Firm firm, String roleName, String permission, UUID actorId) {
        if (firm == null) {
            throw new NotFoundException("Firm not found.");
        }

        if (!staff.hasPermission(firm.getFirmId(), actorId, RolePermission.ADMIN)) {
            throw new NoPermissionException("You don’t have permission to manage roles for " + firm.getDisplayName() + ".");
        }

        boolean roleExists = roles.listRolesByFirm(firm.getFirmId()).stream().anyMatch(r -> r.getRoleName().equalsIgnoreCase(roleName));
        if (!roleExists) {
            throw new NotFoundException("Role not found: " + roleName);
        }

        RolePermission rolePermission = RolePermission.fromString(permission);

        FirmRolePermission frp = new FirmRolePermission(firm.getFirmId(), normalizeRoleName(roleName), rolePermission);

        try {
            roles.addRolePermission(frp);
        } catch (PersistenceException e) {
            throw new ConflictException("Permission already present on role.", e);
        }

        // Granting ADMIN/FINANCIAL must immediately reach Treasury for current
        // holders — the gap that forced manual /business account sync (PAR-141).
        accounts.syncAllFirmAccounts(firm.getFirmId());
    }

    @Override
    public void removeRolePermission(String firmName, String roleName, String permission, UUID actorId) {
        removeRolePermissionInternal(firms.getFirmByNameOrId(firmName), roleName, permission, actorId);
    }

    @Override
    public void removeRolePermission(int firmId, String roleName, String permission, UUID actorId) {
        removeRolePermissionInternal(firms.getFirmById(firmId), roleName, permission, actorId);
    }

    private void removeRolePermissionInternal(Firm firm, String roleName, String permission, UUID actorId) {
        if (firm == null) {
            throw new NotFoundException("Firm not found.");
        }

        if (!staff.hasPermission(firm.getFirmId(), actorId, RolePermission.ADMIN)) {
            throw new NoPermissionException("You don’t have permission to manage roles for " + firm.getDisplayName() + ".");
        }

        RolePermission rolePermission = RolePermission.fromString(permission);
        int removed = roles.deleteRolePermission(firm.getFirmId(), normalizeRoleName(roleName), rolePermission);
        if (removed == 0) {
            throw new NotFoundException("Permission not found on role.");
        }

        // Revoking ADMIN/FINANCIAL must immediately drop Treasury access for
        // current holders, not linger until the next employment event (PAR-141).
        accounts.syncAllFirmAccounts(firm.getFirmId());
    }

    private static String normalizeRoleName(String roleName) {
        return Objects.requireNonNull(roleName, "roleName must not be null").trim();
    }

    @Override
    public List<FirmRole> getFirmRoles(String firmName, UUID actorId) {
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            throw new NotFoundException("Firm not found: " + firmName);
        }

        if (!staff.hasPermission(firm.getFirmId(), actorId, RolePermission.ADMIN)) {
            throw new NoPermissionException("You don’t have permission to manage roles for " + firmName + ".");
        }

        return roles.getFirmRoles(firm.getFirmId());
    }

    @Override
    public List<FirmRolePermission> getFirmRolePermissions(String firmName, String roleName, UUID actorId) {
        Firm firm = firms.getFirmByNameOrId(firmName);
        if (firm == null) {
            throw new NotFoundException("Firm not found: " + firmName);
        }

        if (!staff.hasPermission(firm.getFirmId(), actorId, RolePermission.ADMIN)) {
            throw new NoPermissionException("You don't have permission to manage roles for " + firmName + ".");
        }

        return roles.getFirmRolePermissions(firm.getFirmId(), roleName);
    }

    @Override
    public List<FirmRole> getFirmRoles(int firmId) {
        return roles.getFirmRoles(firmId);
    }

    @Override
    public List<FirmRolePermission> getFirmRolePermissions(int firmId, String roleName) {
        return roles.getFirmRolePermissions(firmId, roleName);
    }
}