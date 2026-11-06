package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.dialogs.FindState;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.FoundShop;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.services.ShopFinderService;
import io.paradaux.chestshop.utils.BusinessAccountUtil;
import io.paradaux.treasury.api.ShopQueryApi;
import io.paradaux.treasury.api.market.ShopResult;
import io.paradaux.treasury.api.market.ShopSearchQuery;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Runs a {@code /find} search off the main thread and returns the sorted,
 * filtered, distance-scored results. The registry read (item exact/LIKE, world,
 * active+visible) happens in Treasury via {@link ShopQueryApi}; the shop-type /
 * hide-empty-full filtering, distance and weighted sort happen here through
 * {@link FindState#pipeline}.
 */
@Singleton
public class ShopFinderServiceImpl implements ShopFinderService {

    private final JavaPlugin plugin;

    private final io.paradaux.chestshop.services.MarketService marketService;

    private final EconomyService economy;
    private final AccountService accounts;

    @Inject
    public ShopFinderServiceImpl(
            JavaPlugin plugin, MarketService marketService, EconomyService economy, AccountService accounts) {
        this.economy = economy;
        this.accounts = accounts;
        this.marketService = marketService;
        this.plugin = plugin;
    }

    @Override
    public boolean available() {
        return marketService.searchEnabled();
    }

    @Override
    public CompletableFuture<List<FoundShop>> find(FindState state) {
        CompletableFuture<List<FoundShop>> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                future.complete(query(state));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** The blocking query + in-memory pipeline. Package-private for testing with a stubbed FindState. */
    List<FoundShop> query(FindState state) {
        ShopQueryApi api = marketService.shopQuery();
        if (api == null) {
            return List.of();
        }
        marketService.awaitWrites();
        List<ShopResult> rows = api.searchShops(ShopSearchQuery.builder()
                .itemKey(state.itemKey())
                .allMatches(true)
                .fuzzy(state.fuzzy())
                .world(state.queryWorld()) // /find is scoped to the player's world (legacy behaviour)
                .build());
        // Re-read balances on each search, once per paying account. Never reserve funds here.
        Map<UUID, BigDecimal> balances = new HashMap<>();
        List<FoundShop> found = rows.stream()
                .filter(r -> state.queryWorldUuid() == null
                        || r.worldUuid() == null
                        || state.queryWorldUuid().equals(r.worldUuid()))
                .map(r -> {
                    boolean roomForBatch =
                            r.estimatedCapacity() == null || r.estimatedCapacity() >= Math.max(1, r.batchQty());
                    BigDecimal sellPrice = roomForBatch ? r.sellPrice() : null;
                    BigDecimal balance = null;
                    boolean unlimitedFunds = false;
                    if (sellPrice != null) {
                        UUID payer = payingAccount(r);
                        unlimitedFunds = r.adminShop() && payer == null;
                        if (payer != null) balance = balances.computeIfAbsent(payer, economy::getBalance);
                        if (sellPrice.signum() > 0
                                && !unlimitedFunds
                                && (balance == null || balance.compareTo(sellPrice) < 0)) sellPrice = null;
                    }
                    if (r.buyPrice() == null && sellPrice == null) return null;
                    return FoundShop.from(
                            r,
                            state.queryWorld(),
                            state.qx(),
                            state.qy(),
                            state.qz(),
                            state.hasPosition(),
                            sellPrice,
                            balance,
                            unlimitedFunds);
                })
                .filter(Objects::nonNull)
                .toList();
        return state.pipeline(found);
    }

    private UUID payingAccount(ShopResult shop) {
        if (shop.adminShop()) {
            Account serverAccount = accounts.getServerEconomyAccount();
            return serverAccount == null ? null : serverAccount.getUuid();
        }
        // The economy adapter accepts an encoded ledger id for any account type.
        return shop.shopAccountId() == null ? null : BusinessAccountUtil.toBusinessUuid(shop.shopAccountId());
    }
}
