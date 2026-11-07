package io.paradaux.business.listeners;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.internal.model.config.BalanceTaxConfiguration;
import io.paradaux.business.services.FirmBalanceTaxService;
import io.paradaux.business.services.FirmBalanceTaxService.BalanceTaxCycleResult;
import io.paradaux.treasury.event.TaxCycleEvent;
import io.paradaux.treasury.model.tax.TaxCycleType;
import java.util.logging.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

@Singleton
public class FirmBalanceTaxListener implements Listener {

    private final BalanceTaxConfiguration config;
    private final FirmBalanceTaxService balanceTaxService;
    private final Logger logger;

    @Inject
    public FirmBalanceTaxListener(
            BalanceTaxConfiguration config,
            FirmBalanceTaxService balanceTaxService,
            io.paradaux.business.Business plugin) {
        this.config = config;
        this.balanceTaxService = balanceTaxService;
        this.logger = plugin.getLogger();
    }

    @EventHandler
    public void onTaxCycle(TaxCycleEvent event) {
        if (event.getCycleType() != TaxCycleType.WEEKLY) return;
        if (!config.isEnabled()) return;

        BalanceTaxCycleResult result = balanceTaxService.runWeeklyCycle(event);
        logger.info("Weekly corporate balance tax cycle: " + result.collected() + " collected, " + result.skipped()
                + " skipped, " + result.failed() + " failed");
    }
}
