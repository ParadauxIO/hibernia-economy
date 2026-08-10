package io.paradaux.treasury.testsupport;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import io.paradaux.treasury.api.TaxApi;
import io.paradaux.treasury.api.impl.TaxApiImpl;
import io.paradaux.treasury.model.config.BalanceTaxConfiguration;
import io.paradaux.treasury.model.config.EconomyConfiguration;
import io.paradaux.treasury.model.config.GovernmentConfiguration;
import io.paradaux.treasury.model.config.SourceIncomeTaxConfiguration;
import io.paradaux.treasury.model.config.TaxCycleConfiguration;

import java.math.BigDecimal;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import io.paradaux.treasury.services.AccountService;
import io.paradaux.treasury.services.BytebinService;
import io.paradaux.treasury.services.EconomyNotifier;
import io.paradaux.treasury.services.DataExportService;
import io.paradaux.treasury.services.GovService;
import io.paradaux.treasury.services.LedgerService;
import io.paradaux.treasury.services.MembershipService;
import io.paradaux.treasury.services.PlayerDirectoryService;
import io.paradaux.treasury.services.BalanceTaxService;
import io.paradaux.treasury.services.TaxCycleRegistry;
import io.paradaux.treasury.services.impl.AccountServiceImpl;
import io.paradaux.treasury.services.impl.BalanceTaxServiceImpl;
import io.paradaux.treasury.services.impl.TaxCycleRegistryImpl;
import io.paradaux.treasury.services.impl.DataExportServiceImpl;
import io.paradaux.treasury.services.impl.GovServiceImpl;
import io.paradaux.treasury.services.impl.PlayerDirectoryServiceImpl;
import io.paradaux.treasury.services.impl.LedgerServiceImpl;
import io.paradaux.treasury.services.impl.MembershipServiceImpl;
import io.paradaux.treasury.services.cache.AccountRedirectCache;
import io.paradaux.treasury.services.cache.PersonalAccountCache;
import io.paradaux.treasury.services.cache.PluginSystemAccountCache;

/**
 * Wires the production service implementations against test configurations.
 *
 * <p>Bukkit-coupled bits (commands, listeners, Vault adapter, Hibernia
 * Message singleton, LuckPerms, the Treasury plugin instance) are intentionally
 * absent — they aren't part of the service-layer contract under test.
 *
 * <p>{@link BytebinService} is bound to a no-op so {@code DataExportService}
 * can be wired without making outbound HTTP calls during tests.
 */
public class TestServicesModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(PluginSystemAccountCache.class).in(Singleton.class);
        bind(AccountRedirectCache.class).in(Singleton.class);
        bind(PersonalAccountCache.class).in(Singleton.class);

        bind(AccountService.class).to(AccountServiceImpl.class).in(Singleton.class);
        bind(MembershipService.class).to(MembershipServiceImpl.class).in(Singleton.class);
        bind(LedgerService.class).to(LedgerServiceImpl.class).in(Singleton.class);
        bind(GovService.class).to(GovServiceImpl.class).in(Singleton.class);
        bind(PlayerDirectoryService.class).to(PlayerDirectoryServiceImpl.class).in(Singleton.class);
        bind(DataExportService.class).to(DataExportServiceImpl.class).in(Singleton.class);

        // No-op notifier: the service layer under test has no Bukkit server to
        // deliver player chat to. Notification content is covered by EconomyNotifierImplTest.
        bind(EconomyNotifier.class).toInstance(new EconomyNotifier() {
            @Override public void notifyTaxCollected(int payerAccountId, String taxType, BigDecimal amount) { }
            @Override public void notifySalaryPaid(java.util.UUID playerUuid, BigDecimal amount) { }
        });

        bind(TaxCycleRegistry.class).to(TaxCycleRegistryImpl.class).in(Singleton.class);
        bind(BalanceTaxService.class).to(BalanceTaxServiceImpl.class).in(Singleton.class);
        bind(TaxApiImpl.class).in(Singleton.class);
        bind(TaxApi.class).to(TaxApiImpl.class).in(Singleton.class);

        bind(BytebinService.class).toInstance((content, contentType) -> "test://upload/" + contentType);
    }

    @Provides @Singleton
    EconomyConfiguration economyConfig() {
        return TestConfigs.economy();
    }

    @Provides @Singleton
    GovernmentConfiguration governmentConfig() {
        return TestConfigs.government();
    }

    @Provides @Singleton
    TaxCycleConfiguration taxCycleConfig() {
        return TestConfigs.taxCyclesAllDisabled();
    }

    @Provides @Singleton
    BalanceTaxConfiguration balanceTaxConfig() {
        NavigableMap<BigDecimal, BigDecimal> brackets = new TreeMap<>();
        brackets.put(new BigDecimal("0.00"),       BigDecimal.ZERO);
        brackets.put(new BigDecimal("100000.00"),  new BigDecimal("0.01"));
        brackets.put(new BigDecimal("200000.00"),  new BigDecimal("0.012"));
        brackets.put(new BigDecimal("500000.00"),  new BigDecimal("0.018"));
        return BalanceTaxConfiguration.forTesting(true, "DCGovernment", brackets);
    }

    @Provides @Singleton
    SourceIncomeTaxConfiguration sourceIncomeTaxConfig() {
        // Disabled by default — tests that need it on should override this binding.
        return SourceIncomeTaxConfiguration.forTesting(
                false, BigDecimal.ZERO, "DCGovernment", Map.of());
    }
}
