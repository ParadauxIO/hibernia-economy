package io.paradaux.business.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import io.paradaux.business.Business;

import io.paradaux.business.api.BusinessApi;
import io.paradaux.business.api.impl.BusinessApiImpl;
import io.paradaux.business.chat.FirmChatService;
import io.paradaux.business.chat.FirmChatServiceImpl;
import io.paradaux.business.integration.RealtyRegionValidator;
import io.paradaux.business.jobs.ExpireRequestsJob;
import io.paradaux.business.model.config.BalanceTaxConfiguration;
import io.paradaux.business.model.config.FirmConfiguration;
import io.paradaux.business.services.*;
import io.paradaux.business.services.impl.*;
import io.paradaux.treasury.api.SalesQueryApi;
import io.paradaux.treasury.api.TreasuryApi;

/**
 * Business-specific Guice wiring: services, the public API, jobs, the Treasury
 * APIs reached over Bukkit services, and the two hand-rolled config singletons.
 *
 * <p>The plugin instance, {@link io.paradaux.hibernia.framework.configurator.ConfigurationLoader},
 * the discovered {@code @ConfigurationComponent}s (DatabaseConfiguration), the
 * {@link io.paradaux.hibernia.framework.i18n.Message} bean, and the command /
 * resolver / listener multibinders are all bound by the framework's
 * {@code HiberniaModule} — this module must not re-bind them.
 */
public class BusinessModule extends AbstractModule {

    private final Business business;
    private final TreasuryApi treasuryApi;
    private final SalesQueryApi salesQueryApi;

    public BusinessModule(Business business, TreasuryApi treasuryApi, SalesQueryApi salesQueryApi) {
        this.business = business;
        this.treasuryApi = treasuryApi;
        this.salesQueryApi = salesQueryApi;
    }

    @Override
    protected void configure() {
        // The concrete plugin type — HiberniaModule binds JavaPlugin/Plugin, but the
        // hand-rolled config singletons inject the Business subclass directly.
        bind(Business.class).toInstance(business);
        bind(TreasuryApi.class).toInstance(treasuryApi);
        bind(SalesQueryApi.class).toInstance(salesQueryApi);

        // Bind services
        bind(FirmAccountService.class).to(FirmAccountServiceImpl.class).in(Singleton.class);
        bind(FirmAreaShopService.class).to(FirmAreaShopServiceImpl.class).in(Singleton.class);
        // Region provider for HQ validation — backed by Realty when installed,
        // reached reflectively as a soft dependency (ADT-37).
        bind(RegionValidator.class).to(RealtyRegionValidator.class).in(Singleton.class);
        bind(FirmRoleService.class).to(FirmRoleServiceImpl.class).in(Singleton.class);
        bind(FirmService.class).to(FirmServiceImpl.class).in(Singleton.class);
        bind(FirmStaffService.class).to(FirmStaffServiceImpl.class).in(Singleton.class);
        bind(FirmTransactionService.class).to(FirmTransactionServiceImpl.class).in(Singleton.class);
        bind(FirmNotificationService.class).to(FirmNotificationServiceImpl.class).in(Singleton.class);
        bind(FirmSalesNotificationService.class).to(FirmSalesNotificationServiceImpl.class).in(Singleton.class);
        bind(FirmDisbandConfirmationService.class).to(FirmDisbandConfirmationServiceImpl.class).in(Singleton.class);
        bind(FirmRequestService.class).to(FirmRequestServiceImpl.class).in(Singleton.class);
        bind(FirmPlayerService.class).to(FirmPlayerServiceImpl.class).in(Singleton.class);
        bind(FirmPropertyService.class).to(FirmPropertyServiceImpl.class).in(Singleton.class);
        bind(FirmBalanceTaxService.class).to(FirmBalanceTaxServiceImpl.class).in(Singleton.class);
        bind(FirmSuggestionCache.class).to(FirmSuggestionCacheImpl.class).in(Singleton.class);
        bind(OnlineRosterCache.class).to(OnlineRosterCacheImpl.class).in(Singleton.class);
        bind(FirmChatService.class).to(FirmChatServiceImpl.class).in(Singleton.class);

        // Configuration (reads from plugin config directly, not the framework
        // configurator — they own their own reload-safe snapshots; do NOT migrate
        // these to @ConfigurationComponent).
        bind(BalanceTaxConfiguration.class).in(Singleton.class);
        bind(FirmConfiguration.class).in(Singleton.class);

        // Bind API
        bind(BusinessApi.class).to(BusinessApiImpl.class).in(Singleton.class);

        // Bind Jobs
        bind(ExpireRequestsJob.class).in(Singleton.class);
    }
}
