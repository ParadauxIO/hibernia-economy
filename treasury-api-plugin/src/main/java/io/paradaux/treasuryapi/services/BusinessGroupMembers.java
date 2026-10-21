package io.paradaux.treasuryapi.services;

import com.google.inject.Inject;
import io.paradaux.business.api.BusinessApi;
import io.paradaux.business.model.RolePermission;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class BusinessGroupMembers {
    private final BusinessApi business;

    @Inject
    public BusinessGroupMembers(BusinessApi business) {
        this.business = business;
    }

    public Set<UUID> resolve(String selector) {
        int id = Integer.parseInt(selector);
        if (id <= 0) throw new IllegalArgumentException("Firm ID must be positive");
        var firm = business.firms().getFirmById(id);
        if (firm == null || Boolean.TRUE.equals(firm.getArchived())) return Set.of();
        Set<UUID> members = new HashSet<>();
        members.add(UUID.fromString(firm.getProprietorUuid()));
        for (var employee : business.staff().getEmployees(id)) {
            UUID player = UUID.fromString(employee.getPlayerUuid());
            if (business.staff().hasPermission(id, player, RolePermission.FINANCIAL)
                    || business.staff().hasPermission(id, player, RolePermission.ADMIN)) members.add(player);
        }
        return members;
    }
}
