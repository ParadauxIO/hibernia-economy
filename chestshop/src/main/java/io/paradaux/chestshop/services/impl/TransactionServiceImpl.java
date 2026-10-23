package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.services.MarketSyncService;
import io.paradaux.chestshop.services.MetricsService;
import io.paradaux.chestshop.services.PostTradeReactions;
import io.paradaux.chestshop.services.SettledPurchasePublisher;
import io.paradaux.chestshop.services.StockCounterService;
import io.paradaux.chestshop.services.TradeContextFactory;
import io.paradaux.chestshop.services.TradeSettlement;
import io.paradaux.chestshop.services.TradeValidator;
import io.paradaux.chestshop.services.TransactionService;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;

/**
 * Thin orchestrator for a shop trade: it delegates the four lifecycle phases to focused
 * collaborators — context construction ({@link TradeContextFactory}), pre-trade validation +
 * messaging ({@link TradeValidator}), atomic goods+money settlement ({@link TradeSettlement}), and
 * the ChestShop-internal post-trade reactions ({@link PostTradeReactions}) — and sequences the
 * genuine cross-cutting hooks (stock counter, market sync, metrics, legacy-sign migration) at the
 * former MONITOR point. The god class this replaced owned all of the above inline
 * (chestshop/structure/0001); the public {@link TransactionService} contract is unchanged.
 */
@Singleton
@Slf4j
public class TransactionServiceImpl implements TransactionService {

    private final TradeContextFactory contextFactory;
    private final TradeValidator validator;
    private final TradeSettlement settlement;
    private final PostTradeReactions postTrade;
    private final EconomyService economy;
    private final StockCounterService stockCounter;
    private final MarketSyncService market;
    private final MetricsService metrics;
    private final SettledPurchasePublisher purchases;

    @Inject
    public TransactionServiceImpl(
            TradeContextFactory contextFactory,
            TradeValidator validator,
            TradeSettlement settlement,
            PostTradeReactions postTrade,
            EconomyService economy,
            StockCounterService stockCounter,
            MarketSyncService market,
            MetricsService metrics,
            SettledPurchasePublisher purchases) {
        this.contextFactory = contextFactory;
        this.validator = validator;
        this.settlement = settlement;
        this.postTrade = postTrade;
        this.economy = economy;
        this.stockCounter = stockCounter;
        this.market = market;
        this.metrics = metrics;
        this.purchases = purchases;
    }

    @Override
    public PendingTransaction prepare(Sign sign, Player player, Action action) {
        return contextFactory.prepare(sign, player, action);
    }

    @Override
    public void validate(PendingTransaction ctx) {
        validator.validate(ctx);
    }

    @Override
    public void clearNotificationCooldowns(UUID playerUuid) {
        validator.clearNotificationCooldowns(playerUuid);
    }

    @Override
    public void process(Transaction event) {
        execute(event);

        // Runs regardless of cancellation. The counter is sign decoration: by now the goods and
        // money have moved, so a failure in it must not skip the sale record and messages below.
        try {
            stockCounter.onTransaction(event);
        } catch (RuntimeException e) {
            log.warn("Could not update the stock counter after a trade", e);
        }

        if (event.isCancelled()) {
            return;
        }
        // Publish before optional cleanup/logging/messages: their failure cannot hide settled goods.
        // Observer failure must not interrupt the already committed trade's remaining reactions.
        try {
            purchases.publish(event);
        } catch (RuntimeException e) {
            log.warn("Could not publish the settled purchase notification", e);
        }
        postTrade.deleteEmptyShop(event); // was @HIGHEST EmptyShopDeleter

        // MONITOR reactions, in registration order.
        economy.migrateLegacyBusinessSign(event);
        market.onTransaction(event); // genuine market-DB sync — stays
        postTrade.logTransaction(event); // was @MONITOR TransactionLogger
        postTrade.sendTransactionMessages(event); // was @MONITOR TransactionMessageSender
        io.paradaux.chestshop.integration.LegacyCompatibility.afterTrade(event);
        metrics.onTransaction(event); // updates the /csmetrics transaction counters
    }

    @Override
    public void execute(Transaction event) {
        settlement.execute(event);
    }
}
