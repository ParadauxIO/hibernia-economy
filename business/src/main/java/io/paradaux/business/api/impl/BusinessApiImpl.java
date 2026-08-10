package io.paradaux.business.api.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.api.*;
import io.paradaux.business.model.*;
import io.paradaux.business.services.*;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Singleton
public class BusinessApiImpl implements BusinessApi {

    private final FirmApi firmApi;
    private final StaffApi staffApi;
    private final RoleApi roleApi;
    private final RequestApi requestApi;
    private final PlayerApi playerApi;

    @Inject
    public BusinessApiImpl(FirmService firmService,
                           FirmStaffService firmStaffService,
                           FirmRoleService firmRoleService,
                           FirmRequestService firmRequestService,
                           FirmPlayerService firmPlayerService,
                           FirmAccountService firmAccountService,
                           FirmTransactionService firmTransactionService) {
        this.firmApi = new FirmApiDelegate(firmService, firmStaffService, firmAccountService, firmTransactionService);
        this.staffApi = new StaffApiDelegate(firmStaffService, firmAccountService);
        this.roleApi = new RoleApiDelegate(firmRoleService);
        this.requestApi = new RequestApiDelegate(firmRequestService);
        this.playerApi = new PlayerApiDelegate(firmPlayerService);
    }

    @Override
    public FirmApi firms() {
        return firmApi;
    }

    @Override
    public StaffApi staff() {
        return staffApi;
    }

    @Override
    public RoleApi roles() {
        return roleApi;
    }

    @Override
    public RequestApi requests() {
        return requestApi;
    }

    @Override
    public PlayerApi players() {
        return playerApi;
    }

    // ---- Delegate implementations ----

    private record FirmApiDelegate(FirmService service, FirmStaffService staff, FirmAccountService accounts,
                                   FirmTransactionService transactions) implements FirmApi {

        @Override
        public Firm getFirm(String nameOrId) {
            // Reads include archived firms so API consumers can still display defunct firms.
            return service.getAnyFirmByNameOrId(nameOrId);
        }

        @Override
        public Firm getFirmById(int firmId) {
            // Typed path: resolve straight by id (archived-inclusive, matching getFirm)
            // instead of the int→String→int round-trip the default would do (ADT-108/96).
            return service.getAnyFirmById(firmId);
        }

        @Override
        public List<Firm> listFirms(int page, int pageSize) {
            return service.listAllFirms(page, pageSize);
        }

        @Override
        public List<Firm> getPlayerFirms(UUID playerId) {
            return service.listOwnedOrMemberFirms(playerId);
        }

        @Override
        public Firm createFirm(String name, UUID proprietorId) {
            return service.createFirm(name, proprietorId);
        }

        @Override
        public void disbandFirm(int firmId, UUID actorId) {
            if (!service.isProprietor(firmId, actorId)) {
                throw new IllegalStateException("Only the proprietor can disband a firm.");
            }
            service.disbandFirm(firmId, actorId);
        }

        @Override
        public void setHq(int firmId, String plotName, UUID actorId) {
            if (!staff.hasPermission(firmId, actorId, RolePermission.ADMIN)) {
                throw new IllegalStateException("You don't have permission to set the HQ for this firm.");
            }
            service.updateFirmHq(firmId, plotName, actorId);
        }

        @Override
        public void setDiscord(int firmId, String url, UUID actorId) {
            if (!staff.hasPermission(firmId, actorId, RolePermission.ADMIN)) {
                throw new IllegalStateException("You don't have permission to set Discord for this firm.");
            }
            service.updateFirmDiscord(firmId, url, actorId);
        }

        @Override
        public boolean isProprietor(int firmId, UUID playerId) {
            return service.isProprietor(firmId, playerId);
        }

        @Override
        public Firm getFirmByAccountId(int accountId) {
            Integer firmId = accounts.getFirmIdByAccountId(accountId);
            if (firmId == null) return null;
            return service.getAnyFirmById(firmId);
        }

        @Override
        public java.math.BigDecimal getTotalBalance(int firmId) {
            return transactions.getAggregateBalance(firmId);
        }

        @Override
        public String getFormattedTotalBalance(int firmId) {
            return transactions.getFormattedAggregateBalance(firmId);
        }
    }

    private record StaffApiDelegate(FirmStaffService service, FirmAccountService accounts) implements StaffApi {

        @Override
        public List<FirmEmployee> getEmployees(int firmId) {
            return service.getCurrentEmployees(firmId);
        }

        @Override
        public List<Player> getOnlineEmployees(int firmId) {
            return service.getOnlineEmployees(firmId);
        }

        @Override
        public boolean isEmployed(int firmId, UUID playerId) {
            return service.isEmployedBy(firmId, playerId);
        }

        @Override
        public String getRole(int firmId, UUID playerId) {
            return service.getCurrentRole(firmId, playerId);
        }

        @Override
        public boolean hasPermission(int firmId, UUID playerId, RolePermission permission) {
            return service.hasPermission(firmId, playerId, permission);
        }

        @Override
        public void hire(int firmId, UUID playerId, UUID actorId) {
            service.hireEmployee(firmId, playerId, actorId);
        }

        @Override
        public void fire(int firmId, UUID playerId, UUID actorId) {
            service.fireEmployee(firmId, playerId, actorId);
        }

        @Override
        public String promote(int firmId, UUID playerId, UUID actorId) {
            return service.promoteEmployee(firmId, playerId, actorId);
        }

        @Override
        public String demote(int firmId, UUID playerId, UUID actorId) {
            return service.demoteEmployee(firmId, playerId, actorId);
        }

        @Override
        public void resign(int firmId, UUID playerId) {
            service.resignFromFirm(firmId, playerId);
        }

        @Override
        public boolean hasPermissionForAccount(int accountId, UUID playerId, RolePermission permission) {
            Integer firmId = accounts.getFirmIdByAccountId(accountId);
            if (firmId == null) return false;
            return service.hasPermission(firmId, playerId, permission);
        }
    }

    private record RoleApiDelegate(FirmRoleService service) implements RoleApi {

        @Override
        public List<FirmRole> getRoles(int firmId) {
            return service.getFirmRoles(firmId);
        }

        @Override
        public List<FirmRolePermission> getRolePermissions(int firmId, String roleName) {
            return service.getFirmRolePermissions(firmId, roleName);
        }

        @Override
        public void createRole(int firmId, String roleName, int rankOrder, UUID actorId) {
            service.createRole(firmId, roleName, rankOrder, actorId);
        }

        @Override
        public void deleteRole(int firmId, String roleName, UUID actorId) {
            service.deleteRole(firmId, roleName, actorId);
        }

        @Override
        public void addPermission(int firmId, String roleName, String permission, UUID actorId) {
            service.addRolePermission(firmId, roleName, permission, actorId);
        }

        @Override
        public void removePermission(int firmId, String roleName, String permission, UUID actorId) {
            service.removeRolePermission(firmId, roleName, permission, actorId);
        }
    }

    private record RequestApiDelegate(FirmRequestService service) implements RequestApi {

        @Override
        public void offerEmployment(int firmId, UUID playerId, UUID actorId) {
            service.offerEmployment(firmId, playerId, actorId);
        }

        @Override
        public void rescindOffer(int firmId, UUID playerId, UUID actorId) {
            service.rescindEmploymentOffer(firmId, playerId, actorId);
        }

        @Override
        public void acceptOffer(int firmId, UUID playerId, UUID actorId) {
            service.acceptEmploymentOffer(firmId, playerId, actorId);
        }

        @Override
        public void rejectOffer(int firmId, UUID playerId, UUID actorId) {
            service.rejectEmploymentOffer(firmId, playerId, actorId);
        }
    }

    private record PlayerApiDelegate(FirmPlayerService service) implements PlayerApi {

        @Override
        public Optional<FirmPlayer> findByUuid(UUID uuid) {
            return service.findByUuid(uuid);
        }

        @Override
        public Optional<FirmPlayer> findByName(String name) {
            return service.findByName(name);
        }

        @Override
        public List<FirmPlayer> searchByPrefix(String prefix, int limit) {
            return service.searchByPrefix(prefix, limit);
        }
    }
}
