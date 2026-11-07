package io.paradaux.business.internal.api;

import io.paradaux.business.model.Firm;
import io.paradaux.business.model.FirmEmployee;
import io.paradaux.business.model.FirmPlayer;
import io.paradaux.business.model.FirmRole;
import io.paradaux.business.model.FirmRolePermission;
import java.util.UUID;

/** Copies service-owned mutable rows at the published API boundary. */
final class ApiSnapshots {
    private ApiSnapshots() {}

    static Firm firm(io.paradaux.business.internal.model.Firm row) {
        if (row == null) return null;
        return new Firm(
                row.getFirmId(),
                row.getDisplayName(),
                uuid(row.getProprietorUuid()),
                row.getDiscordUrl(),
                row.getHqRegion(),
                row.getDefaultAccountId(),
                row.getArchived(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    static FirmEmployee employee(io.paradaux.business.internal.model.FirmEmployee row) {
        return new FirmEmployee(
                row.getFirmId(),
                uuid(row.getPlayerUuid()),
                row.getRoleName(),
                row.getJoinedAt(),
                row.getLeftAt(),
                uuid(row.getAddedBy()),
                uuid(row.getRemovedBy()),
                row.getCurrent());
    }

    static FirmRole role(io.paradaux.business.internal.model.FirmRole row) {
        return new FirmRole(row.getFirmId(), row.getRoleName(), row.getRoleRankOrder());
    }

    static FirmRolePermission permission(io.paradaux.business.internal.model.FirmRolePermission row) {
        return new FirmRolePermission(row.getFirmId(), row.getRoleName(), row.getPermission());
    }

    static FirmPlayer player(io.paradaux.business.internal.model.FirmPlayer row) {
        // Preserve the player directory's documented tolerant UUID parsing.
        return new FirmPlayer(
                row.getUniqueId(), row.getCurrentName(), row.getNameLower(), row.getFirstSeen(), row.getLastSeen());
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
