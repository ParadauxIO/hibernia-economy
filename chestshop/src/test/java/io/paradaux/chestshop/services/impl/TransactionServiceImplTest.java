package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.services.PostTradeReactions;
import io.paradaux.chestshop.services.TradeContextFactory;
import io.paradaux.chestshop.services.TradeSettlement;
import io.paradaux.chestshop.services.TradeValidator;

import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.services.MarketSyncService;
import io.paradaux.chestshop.services.MetricsService;
import io.paradaux.chestshop.services.StockCounterService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.math.BigDecimal;
import java.util.UUID;

import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Orchestration coverage for {@link TransactionServiceImpl}: it is a thin delegator, so this test
 * verifies only the sequencing of {@code process(...)} across the four collaborators + the
 * cross-cutting hooks, and the pass-through of {@code prepare}/{@code validate}/{@code execute}/
 * {@code clearNotificationCooldowns}. Every collaborator is a Mockito mock; the economy is a
 * {@link FakeEconomy} hand-double (mocking {@code EconomyService} loads Treasury types absent from
 * the test runtime), tracking {@code migrateCalls}. The per-phase behaviour lives in the split
 * TradeContextFactoryTest / TradeValidatorTest / TradeSettlementTest / PostTradeReactionsTest
 * (chestshop/structure/0001).
 */
class TransactionServiceImplTest {

    private TradeContextFactory contextFactory;
    private TradeValidator validator;
    private TradeSettlement settlement;
    private PostTradeReactions postTrade;
    private FakeEconomy economy;
    private StockCounterService stockCounter;
    private MarketSyncService market;
    private MetricsService metrics;

    private TransactionServiceImpl service;

    @BeforeEach
    void setUp() {
        contextFactory = mock(TradeContextFactory.class);
        validator = mock(TradeValidator.class);
        settlement = mock(TradeSettlement.class);
        postTrade = mock(PostTradeReactions.class);
        economy = new FakeEconomy();
        stockCounter = mock(StockCounterService.class);
        market = mock(MarketSyncService.class);
        metrics = mock(MetricsService.class);

        service = new TransactionServiceImpl(contextFactory, validator, settlement, postTrade, economy,
                stockCounter, market, metrics);
    }

    // ── builders ──────────────────────────────────────────────────────────────

    private ItemStack item(Material type, int amount) {
        int[] amt = {amount};
        Answer<Object> behaviour = inv -> switch (inv.getMethod().getName()) {
            case "getType" -> type;
            case "getAmount" -> amt[0];
            case "setAmount" -> { amt[0] = inv.getArgument(0); yield null; }
            case "clone" -> item(type, amt[0]);
            case "toString" -> "Item(" + type + "x" + amt[0] + ")";
            case "equals" -> inv.getMock() == inv.getArgument(0);
            case "hashCode" -> System.identityHashCode(inv.getMock());
            default -> org.mockito.Mockito.RETURNS_DEFAULTS.answer(inv);
        };
        return mock(ItemStack.class, behaviour);
    }

    private Player player(String name, UUID id) {
        Player p = mock(Player.class);
        lenient().when(p.getName()).thenReturn(name);
        lenient().when(p.getUniqueId()).thenReturn(id);
        lenient().when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);
        lenient().when(p.getInventory()).thenReturn(mock(PlayerInventory.class));
        return p;
    }

    private Location location(World world) {
        Location loc = mock(Location.class);
        lenient().when(loc.getWorld()).thenReturn(world);
        lenient().when(loc.getBlockX()).thenReturn(1);
        lenient().when(loc.getBlockY()).thenReturn(64);
        lenient().when(loc.getBlockZ()).thenReturn(2);
        return loc;
    }

    private Sign sign(Location loc) {
        World w = loc.getWorld();
        Sign s = mock(Sign.class);
        lenient().when(s.getLocation()).thenReturn(loc);
        lenient().when(s.getWorld()).thenReturn(w);
        lenient().when(s.getLines()).thenReturn(new String[]{"Alice", "1", "B 5", "STONE"});
        return s;
    }

    private Transaction txn(Transaction.TransactionType type, Sign s, Player client, Account owner,
                            Inventory ownerInv, Inventory clientInv, ItemStack[] stock, BigDecimal price) {
        PendingTransaction pending = new PendingTransaction(ownerInv, clientInv, stock, price, client, owner, s, type, false);
        return new Transaction(pending, s);
    }

    private Transaction sampleTransaction() {
        Player client = player("Notch", UUID.randomUUID());
        Account owner = new Account("Alice", "Alice", UUID.randomUUID());
        return txn(BUY, sign(location(mock(World.class))), client, owner, mock(Inventory.class),
                mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5.00"));
    }

    // ═══════════════════════════ delegating methods ═══════════════════════════

    @Test
    void prepare_delegatesToContextFactory() {
        Sign s = sign(location(mock(World.class)));
        Player p = player("Notch", UUID.randomUUID());
        PendingTransaction expected = mock(PendingTransaction.class);
        lenient().when(contextFactory.prepare(s, p, Action.RIGHT_CLICK_BLOCK)).thenReturn(expected);

        assertThat(service.prepare(s, p, Action.RIGHT_CLICK_BLOCK)).isSameAs(expected);
        verify(contextFactory).prepare(s, p, Action.RIGHT_CLICK_BLOCK);
    }

    @Test
    void validate_delegatesToValidator() {
        PendingTransaction ctx = mock(PendingTransaction.class);
        service.validate(ctx);
        verify(validator).validate(ctx);
    }

    @Test
    void clearNotificationCooldowns_delegatesToValidator() {
        UUID id = UUID.randomUUID();
        service.clearNotificationCooldowns(id);
        verify(validator).clearNotificationCooldowns(id);
    }

    @Test
    void execute_delegatesToSettlement() {
        Transaction event = sampleTransaction();
        service.execute(event);
        verify(settlement).execute(event);
    }

    // ═══════════════════════════ process() sequencing ═══════════════════════════

    @Test
    void process_nonCancelledTrade_runsFullSequence() {
        Transaction event = sampleTransaction(); // settlement mock leaves it non-cancelled

        service.process(event);

        verify(settlement).execute(event);
        verify(stockCounter).onTransaction(event);
        verify(postTrade).deleteEmptyShop(event);
        assertThat(economy.migrateCalls).isEqualTo(1);
        verify(market).onTransaction(event);
        verify(postTrade).logTransaction(event);
        verify(postTrade).sendTransactionMessages(event);
        verify(metrics).onTransaction(event);
    }

    @Test
    void process_cancelledTrade_stopsAfterStockCounter() {
        Transaction event = sampleTransaction();
        // execute is what cancels the trade — model that via the settlement mock.
        doAnswer(inv -> { ((Transaction) inv.getArgument(0)).setCancelled(true); return null; })
                .when(settlement).execute(event);

        service.process(event);

        verify(settlement).execute(event);
        verify(stockCounter).onTransaction(event); // runs regardless of cancellation
        verify(postTrade, never()).deleteEmptyShop(any());
        verify(market, never()).onTransaction(any());
        verify(postTrade, never()).logTransaction(any());
        verify(postTrade, never()).sendTransactionMessages(any());
        verify(metrics, never()).onTransaction(any());
        assertThat(economy.migrateCalls).isZero();
    }

    /**
     * Hand-written {@link io.paradaux.chestshop.services.EconomyService} double. It can't be a
     * Mockito mock: the interface's {@code bind(TreasuryApi, int, TaxApi)} forces ByteBuddy to load
     * Treasury API types that are deliberately absent from the test runtime. A concrete empty
     * {@code bind} body avoids that. Declared {@code private} so the JUnit Platform discovery scan
     * (which calls {@code getMethods()} on non-private test-source classes) skips it before it would
     * hit the same missing-type resolution. Behaviour is driven through public fields.
     */
    private static final class FakeEconomy implements io.paradaux.chestshop.services.EconomyService {
        boolean ownerEconomicallyActive = true;
        java.util.function.BiPredicate<java.util.UUID, java.math.BigDecimal> hasFunds = (u, a) -> true;
        java.util.function.Function<java.util.UUID, java.math.BigDecimal> balance = u -> java.math.BigDecimal.ZERO;
        boolean hasAccount = true;
        boolean settleResult = true;
        int settleCalls = 0;
        int migrateCalls = 0;
        @Override public void bind(io.paradaux.treasury.api.TreasuryApi treasury, int systemAccountId, io.paradaux.treasury.api.TaxApi taxApi) { /* unused headless */ }
        @Override public String format(java.math.BigDecimal amount) { return amount.toPlainString(); }
        @Override public boolean isOwnerEconomicallyActive(boolean unlimitedOwner) { return ownerEconomicallyActive; }
        @Override public boolean deposit(java.util.UUID target, java.math.BigDecimal amount, org.bukkit.World world) { return true; }
        @Override public boolean withdraw(java.util.UUID target, java.math.BigDecimal amount, org.bukkit.World world) { return true; }
        @Override public boolean hasFunds(java.util.UUID account, java.math.BigDecimal amount) { return hasFunds.test(account, amount); }
        @Override public java.math.BigDecimal getBalance(java.util.UUID account) { return balance.apply(account); }
        @Override public boolean hasAccount(java.util.UUID account) { return hasAccount; }
        @Override public boolean settle(java.math.BigDecimal amount, org.bukkit.entity.Player initiator, java.util.UUID partner, io.paradaux.chestshop.model.Transaction txn) { settleCalls++; return settleResult; }
        @Override public void migrateLegacyBusinessSign(io.paradaux.chestshop.model.Transaction event) { migrateCalls++; }
    }
}
