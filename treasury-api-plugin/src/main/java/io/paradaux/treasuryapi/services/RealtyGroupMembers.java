package io.paradaux.treasuryapi.services;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;

/** Optional RealtyBackend API bridge, matching Business's reflection-based integration.
 * Uses contract holders, never authority/landlord account members or online-player lists.
 */
public class RealtyGroupMembers {
    private static final String BACKEND = "io.github.md5sha256.realty.api.RealtyBackend";

    public Set<UUID> resolve(String selector) throws ReflectiveOperationException {
        String[] parts = selector.split("/", 2);
        if (parts.length != 2 || parts[1].isBlank())
            throw new IllegalArgumentException("Expected world-uuid/region-id");
        UUID world = UUID.fromString(parts[0]);
        Class<?> api = Class.forName(BACKEND);
        Object backend = Bukkit.getServicesManager().load(api);
        if (backend == null) throw new IllegalStateException("RealtyBackend unavailable");
        Object info = Objects.requireNonNull(
                api.getMethod("getRegionInfo", String.class, UUID.class).invoke(backend, parts[1], world),
                "Realty returned no region snapshot");
        return members(info, LocalDateTime.now(java.time.ZoneId.systemDefault()));
    }

    static Set<UUID> members(Object info, LocalDateTime now) throws ReflectiveOperationException {
        Set<UUID> members = new HashSet<>();
        Object freehold = read(info, "freehold");
        if (freehold != null) {
            UUID owner = (UUID) read(freehold, "titleHolderId");
            if (owner != null) members.add(owner);
        }
        Object lease = read(info, "leasehold");
        if (lease != null) {
            UUID tenant = (UUID) read(lease, "tenantId");
            LocalDateTime end = (LocalDateTime) read(lease, "endDate");
            LocalDateTime termination = (LocalDateTime) read(lease, "terminationEffectiveDate");
            if (tenant != null && end != null && end.isAfter(now) && (termination == null || termination.isAfter(now)))
                members.add(tenant);
        }
        return members;
    }

    private static Object read(Object value, String accessor) throws ReflectiveOperationException {
        return value.getClass().getMethod(accessor).invoke(value);
    }
}
