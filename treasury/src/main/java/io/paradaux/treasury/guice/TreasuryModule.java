package io.paradaux.treasury.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import io.paradaux.treasury.Treasury;
import io.paradaux.treasury.api.MarketApi;
import io.paradaux.treasury.api.SalesQueryApi;
import io.paradaux.treasury.api.ShopQueryApi;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.api.impl.MarketApiImpl;
import io.paradaux.treasury.api.impl.SalesQueryApiImpl;
import io.paradaux.treasury.api.impl.ShopQueryApiImpl;
import io.paradaux.treasury.api.impl.TaxApiImpl;
import io.paradaux.treasury.events.FirstPlayerJoinEvent;
import io.paradaux.treasury.events.PlayerLoginListener;
import io.paradaux.treasury.model.config.BalanceTaxConfiguration;
import io.paradaux.treasury.model.config.SalaryConfiguration;
import io.paradaux.treasury.model.config.SourceIncomeTaxConfiguration;
import io.paradaux.treasury.services.*;
import io.paradaux.treasury.services.impl.*;
import io.paradaux.treasury.services.cache.AccountRedirectCache;
import io.paradaux.treasury.services.cache.PersonalAccountCache;
import io.paradaux.treasury.services.cache.PluginSystemAccountCache;
import net.luckperms.api.LuckPerms;
import org.bukkit.Server;

/**
 * Treasury's domain-service bindings. Plugin/ConfigurationLoader/Message and all
 * {@code @ConfigurationComponent} singletons are bound by {@link
 * io.paradaux.hibernia.framework.guice.HiberniaModule HiberniaModule}; this module
 * must not re-bind any of them.
 */
public class TreasuryModule extends AbstractModule {

    private final Treasury treasury;

    public TreasuryModule(Treasury treasury) {
        this.treasury = treasury;
    }

    @Override
    protected void configure() {
        // Concrete plugin type (HiberniaModule binds only JavaPlugin/Plugin; several
        // Treasury injectees depend on the concrete Treasury type).
        bind(Treasury.class).toInstance(treasury);

        // Utilities
        bind(PluginSystemAccountCache.class).in(Singleton.class);
        bind(AccountRedirectCache.class).in(Singleton.class);
        bind(PersonalAccountCache.class).in(Singleton.class);

        // Domain services
        bind(AccountService.class).to(AccountServiceImpl.class).in(Singleton.class);
        bind(MembershipService.class).to(MembershipServiceImpl.class).in(Singleton.class);
        bind(LedgerService.class).to(LedgerServiceImpl.class).in(Singleton.class);
        bind(BytebinService.class).to(BytebinServiceImpl.class).in(Singleton.class);
        bind(DataExportService.class).to(DataExportServiceImpl.class).in(Singleton.class);
        bind(GovService.class).to(GovServiceImpl.class).in(Singleton.class);
        bind(FineWebhookService.class).to(FineWebhookServiceImpl.class).in(Singleton.class);
        bind(PlayerDirectoryService.class).to(PlayerDirectoryServiceImpl.class).in(Singleton.class);
        bind(AuditService.class).to(AuditServiceImpl.class).in(Singleton.class);
        bind(ConfigReloadService.class).to(ConfigReloadServiceImpl.class).in(Singleton.class);

        // Player-facing notifications for automated money movement (tax, salaries).
        bind(EconomyNotifier.class).to(EconomyNotifierImpl.class).in(Singleton.class);

        // Tax API
        bind(SourceIncomeTaxConfiguration.class).in(Singleton.class);
        bind(BalanceTaxConfiguration.class).in(Singleton.class);
        bind(BalanceTaxService.class).to(BalanceTaxServiceImpl.class).in(Singleton.class);
        bind(TaxCycleRegistry.class).to(TaxCycleRegistryImpl.class).in(Singleton.class);
        bind(TaxApiImpl.class).in(Singleton.class);
        bind(TaxApi.class).to(TaxApiImpl.class).in(Singleton.class);
        bind(TaxWebhookService.class).to(TaxWebhookServiceImpl.class).in(Singleton.class);

        // ChestShop market data (sales tracker + live shop registry)
        bind(MarketApiImpl.class).in(Singleton.class);
        bind(MarketApi.class).to(MarketApiImpl.class).in(Singleton.class);
        bind(SalesQueryApiImpl.class).in(Singleton.class);
        bind(SalesQueryApi.class).to(SalesQueryApiImpl.class).in(Singleton.class);
        bind(ShopQueryApiImpl.class).in(Singleton.class);
        bind(ShopQueryApi.class).to(ShopQueryApiImpl.class).in(Singleton.class);

        // Government salaries — LuckPerms-group-based payouts on a timer.
        bind(Server.class).toInstance(treasury.getServer());
        bind(SalaryConfiguration.class).in(Singleton.class);
        bind(SalaryService.class).to(SalaryServiceImpl.class).in(Singleton.class);

        // Bukkit listeners. HiberniaModule's .listeners(...) adds these to the
        // Set<Listener> multibinder (unscoped); bind them as singletons here so
        // a single instance is created and registered at enable.
        bind(FirstPlayerJoinEvent.class).in(Singleton.class);
        bind(PlayerLoginListener.class).in(Singleton.class);

        // LuckPerms (optional softdepend). Guard the class reference behind a
        // Class.forName check — like the Vault adapter — so the JVM never has to
        // resolve net.luckperms.api.LuckPerms when the plugin isn't installed.
        // Without this guard the bare LuckPerms.class literal below throws
        // NoClassDefFoundError at enable when LuckPerms is absent, disabling
        // Treasury (and every plugin that depends on it). MembershipService and
        // SalaryService already inject LuckPerms via @Inject(optional = true).
        if (isClassPresent("net.luckperms.api.LuckPerms")) {
            LuckPerms lp = treasury.getServer().getServicesManager().load(LuckPerms.class);
            if (lp != null) {
                bind(LuckPerms.class).toInstance(lp);
            }
        }
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
