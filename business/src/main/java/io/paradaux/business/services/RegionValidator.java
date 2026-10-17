package io.paradaux.business.services;

import java.util.Optional;

/**
 * Resolves whether a region/plot id is a real, managed property, delegating to
 * whatever region provider is installed (Realty). Kept as a narrow seam so the
 * Bukkit/reflection wiring stays out of the testable service layer (ADT-37).
 */
public interface RegionValidator {

    /**
     * @param regionId the region/plot id to check (a WorldGuard region id)
     * @return {@code Optional.of(true)} if a provider recognises the region,
     *         {@code Optional.of(false)} if a provider is present but does not,
     *         and {@code Optional.empty()} when no region provider is available
     *         (the caller decides how to treat the unknown case).
     */
    Optional<Boolean> validate(String regionId);
}
