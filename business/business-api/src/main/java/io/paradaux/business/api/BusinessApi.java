package io.paradaux.business.api;

/**
 * Main entry point for the Business plugin public API.
 *
 * <p>Obtain an instance via Bukkit's {@code ServicesManager}:
 * <pre>{@code
 * BusinessApi api = Bukkit.getServicesManager()
 *     .getRegistration(BusinessApi.class)
 *     .getProvider();
 * api.firms().getFirm("MyCompany");
 * api.staff().isEmployed(firmId, playerUuid);
 * }</pre>
 *
 * <p>Model results are immutable snapshots, with UUID-typed player identifiers.
 * Lists returned by this provider are unmodifiable snapshots. Fetch again to
 * observe later changes; use API operations to modify a firm, staff or roles.
 * Bukkit {@code Player} objects in {@link StaffApi#getOnlineEmployees(int)} remain
 * live server objects and must follow Bukkit's thread-access rules.
 *
 * <p>API methods may throw {@link RuntimeException} subclasses for invalid
 * operations (e.g., entity not found, insufficient permissions, bad input).
 */
public interface BusinessApi {

    /**
     * Returns the firm operations API.
     */
    FirmApi firms();

    /**
     * Returns the staff/employment operations API.
     */
    StaffApi staff();

    /**
     * Returns the role and permission management API.
     */
    RoleApi roles();

    /**
     * Returns the employment request operations API.
     */
    RequestApi requests();

    /**
     * Returns the player lookup API.
     */
    PlayerApi players();
}
