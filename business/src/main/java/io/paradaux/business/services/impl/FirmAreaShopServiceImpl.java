package io.paradaux.business.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.services.FirmAreaShopService;
import io.paradaux.business.services.RegionValidator;

@Singleton
public class FirmAreaShopServiceImpl implements FirmAreaShopService {

    private final RegionValidator regionValidator;

    @Inject
    public FirmAreaShopServiceImpl(RegionValidator regionValidator) {
        this.regionValidator = regionValidator;
    }

    /**
     * Validates a firm HQ plot against the region provider (Realty).
     *
     * <p>Blank ids are always rejected. Otherwise the decision is delegated to
     * {@link RegionValidator}; when no provider is available we <em>fail open</em>
     * and accept the plot, so HQ-setting keeps working on servers that don't run
     * Realty (ADT-37). Previously this was an unconditional {@code return true}.
     */
    @Override
    public boolean isValidPlot(String plotName) {
        if (plotName == null || plotName.isBlank()) {
            return false;
        }
        return regionValidator.validate(plotName).orElse(true);
    }
}
