package io.paradaux.chestshop.guice;

import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import io.paradaux.chestshop.integration.GriefPreventionIntegration;
import io.paradaux.chestshop.integration.Integration;
import io.paradaux.chestshop.integration.NexoIntegration;
import io.paradaux.chestshop.integration.TreasuryIntegration;
import io.paradaux.chestshop.integration.WorldGuardIntegration;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.AdminBypassService;
import io.paradaux.chestshop.services.BusinessAccountService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.services.GiveService;
import io.paradaux.chestshop.services.GoodsTransfer;
import io.paradaux.chestshop.services.InfoService;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.ItemCodeService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.MarketResyncService;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.services.MarketSyncService;
import io.paradaux.chestshop.services.MaterialService;
import io.paradaux.chestshop.services.MetricsService;
import io.paradaux.chestshop.services.PartialFillCalculator;
import io.paradaux.chestshop.services.PostTradeReactions;
import io.paradaux.chestshop.services.PreviewService;
import io.paradaux.chestshop.services.ProtectionService;
import io.paradaux.chestshop.services.RestrictedSignService;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.ShopFinderService;
import io.paradaux.chestshop.services.ShopService;
import io.paradaux.chestshop.services.SignBreakService;
import io.paradaux.chestshop.services.StockCounterService;
import io.paradaux.chestshop.services.TradeContextFactory;
import io.paradaux.chestshop.services.TradeSettlement;
import io.paradaux.chestshop.services.TradeValidator;
import io.paradaux.chestshop.services.TransactionService;
import io.paradaux.chestshop.services.impl.AccountServiceImpl;
import io.paradaux.chestshop.services.impl.AdminBypassServiceImpl;
import io.paradaux.chestshop.services.impl.BusinessAccountServiceImpl;
import io.paradaux.chestshop.services.impl.EconomyServiceImpl;
import io.paradaux.chestshop.services.impl.GiveServiceImpl;
import io.paradaux.chestshop.services.impl.InfoServiceImpl;
import io.paradaux.chestshop.services.impl.InventoryServiceImpl;
import io.paradaux.chestshop.services.impl.ItemCodeServiceImpl;
import io.paradaux.chestshop.services.impl.ItemServiceImpl;
import io.paradaux.chestshop.services.impl.MarketResyncServiceImpl;
import io.paradaux.chestshop.services.impl.MarketSyncServiceImpl;
import io.paradaux.chestshop.services.impl.MaterialServiceImpl;
import io.paradaux.chestshop.services.impl.PreviewServiceImpl;
import io.paradaux.chestshop.services.impl.ProtectionServiceImpl;
import io.paradaux.chestshop.services.impl.RestrictedSignServiceImpl;
import io.paradaux.chestshop.services.impl.ShopBlockServiceImpl;
import io.paradaux.chestshop.services.impl.ShopFinderServiceImpl;
import io.paradaux.chestshop.services.impl.ShopServiceImpl;
import io.paradaux.chestshop.services.impl.SignBreakServiceImpl;
import io.paradaux.chestshop.services.impl.StockCounterServiceImpl;
import io.paradaux.chestshop.services.impl.TransactionServiceImpl;

/**
 * ChestShop's own Guice bindings — its service layer, alongside the framework's
 * {@code HiberniaModule} and the {@link DatabaseModule} that wires the MyBatis mappers.
 * This is the seam the plugin migrated onto: thin entrypoints → {@code services/}
 * (business logic) → {@code mappers/} (MyBatis persistence), replacing the static
 * God-classes and the internal event-bus pipelines. Each service is a {@code services/}
 * interface bound to its {@code services/impl/} implementation (PAR-306); the mappers run
 * over SQLite through MyBatis, the same service→mapper shape the other plugins use (PAR-282).
 */
public class ChestShopModule extends AbstractModule {

    private final java.io.File dataFolder;

    public ChestShopModule(java.io.File dataFolder) {
        this.dataFolder = dataFolder;
    }

    @Override
    protected void configure() {
        bind(java.io.File.class).annotatedWith(com.google.inject.name.Names.named("dataFolder")).toInstance(dataFolder);
        bind(AccountService.class).to(AccountServiceImpl.class).in(Singleton.class);
        bind(AdminBypassService.class).to(AdminBypassServiceImpl.class).in(Singleton.class);
        bind(BusinessAccountService.class).to(BusinessAccountServiceImpl.class).in(Singleton.class);
        bind(EconomyService.class).to(EconomyServiceImpl.class).in(Singleton.class);
        bind(GiveService.class).to(GiveServiceImpl.class).in(Singleton.class);
        bind(InfoService.class).to(InfoServiceImpl.class).in(Singleton.class);
        bind(InventoryService.class).to(InventoryServiceImpl.class).in(Singleton.class);
        bind(ItemCodeService.class).to(ItemCodeServiceImpl.class).in(Singleton.class);
        bind(ItemService.class).to(ItemServiceImpl.class).in(Singleton.class);
        bind(MarketResyncService.class).to(MarketResyncServiceImpl.class).in(Singleton.class);
        bind(MaterialService.class).to(MaterialServiceImpl.class).in(Singleton.class);
        bind(PreviewService.class).to(PreviewServiceImpl.class).in(Singleton.class);
        bind(ProtectionService.class).to(ProtectionServiceImpl.class).in(Singleton.class);
        bind(ShopBlockService.class).to(ShopBlockServiceImpl.class).in(Singleton.class);
        bind(ShopFinderService.class).to(ShopFinderServiceImpl.class).in(Singleton.class);
        bind(ShopService.class).to(ShopServiceImpl.class).in(Singleton.class);
        bind(TransactionService.class).to(TransactionServiceImpl.class).in(Singleton.class);
        // The trade-pipeline collaborators the orchestrator sequences (chestshop/plugin-architecture/0008).
        bind(TradeContextFactory.class).to(io.paradaux.chestshop.services.impl.TradeContextFactoryImpl.class).in(Singleton.class);
        bind(TradeValidator.class).to(io.paradaux.chestshop.services.impl.TradeValidatorImpl.class).in(Singleton.class);
        bind(PartialFillCalculator.class).to(io.paradaux.chestshop.services.impl.PartialFillCalculatorImpl.class).in(Singleton.class);
        bind(TradeSettlement.class).to(io.paradaux.chestshop.services.impl.TradeSettlementImpl.class).in(Singleton.class);
        bind(GoodsTransfer.class).to(io.paradaux.chestshop.services.impl.GoodsTransferImpl.class).in(Singleton.class);
        bind(PostTradeReactions.class).to(io.paradaux.chestshop.services.impl.PostTradeReactionsImpl.class).in(Singleton.class);
        bind(MarketService.class).to(io.paradaux.chestshop.services.impl.MarketServiceImpl.class).in(Singleton.class);
        bind(MarketSyncService.class).to(MarketSyncServiceImpl.class).in(Singleton.class);
        bind(MetricsService.class).to(io.paradaux.chestshop.services.impl.MetricsServiceImpl.class).in(Singleton.class);
        bind(RestrictedSignService.class).to(RestrictedSignServiceImpl.class).in(Singleton.class);
        bind(SignBreakService.class).to(SignBreakServiceImpl.class).in(Singleton.class);
        bind(StockCounterService.class).to(StockCounterServiceImpl.class).in(Singleton.class);

        // SignService is a static-heavy sign-format util with a small instance surface,
        // not an interface/impl service — bound concrete.
        bind(SignService.class).in(Singleton.class);

        // Soft-dependency integrations — each detected + hooked by the IntegrationRegistrar
        // (PAR-307). Adding a new one is a new Integration + one addBinding line here.
        Multibinder<Integration> integrations = Multibinder.newSetBinder(binder(), Integration.class);
        integrations.addBinding().to(TreasuryIntegration.class);
        integrations.addBinding().to(WorldGuardIntegration.class);
        integrations.addBinding().to(GriefPreventionIntegration.class);
        integrations.addBinding().to(NexoIntegration.class);
    }
}
