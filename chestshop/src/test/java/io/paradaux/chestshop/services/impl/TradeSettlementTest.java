package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.services.GoodsTransfer;
import io.paradaux.chestshop.services.TradeSettlement;

import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.Transaction;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.math.BigDecimal;
import java.util.UUID;

import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;
import static io.paradaux.chestshop.model.Transaction.TransactionType.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for {@link TradeSettlement#execute}: atomic goods+money settlement with rollback,
 * unlimited admin-shop spawn/vanish path, and the shortfall abort. The economy is a
 * {@link FakeEconomy} hand-double (its {@code bind} signature references Treasury types absent from
 * the test runtime); {@link GoodsTransfer} is a Mockito mock. Split out of the former
 * TransactionServiceImplTest (chestshop/structure/0001).
 */
class TradeSettlementTest {

    private FakeEconomy economy;
    private GoodsTransfer goodsTransfer;

    private TradeSettlement settlement;

    @BeforeEach
    void setUp() {
        economy = new FakeEconomy();
        goodsTransfer = mock(GoodsTransfer.class);
        settlement = new TradeSettlementImpl(economy, goodsTransfer);
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

    /** A completed-trade context. */
    private Transaction txn(Transaction.TransactionType type, boolean unlimited, Sign s,
                            Player client, Account owner, Inventory ownerInv, Inventory clientInv,
                            ItemStack[] stock, BigDecimal price) {
        PendingTransaction pending = new PendingTransaction(ownerInv, clientInv, stock, price, client, owner, s, type, unlimited);
        return new Transaction(pending, s);
    }

    // ═══════════════════════════ execute() ═══════════════════════════

    @Test
    void execute_movesGoodsAndSettles_forBuy() {
        Player client = player("Notch", UUID.randomUUID());
        Account owner = new Account("Alice", "Alice", UUID.randomUUID());
        Inventory ownerInv = mock(Inventory.class);
        Inventory clientInv = mock(Inventory.class);
        Transaction event = txn(BUY, false, sign(location(mock(World.class))), client, owner, ownerInv, clientInv,
                new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5.00"));
        when(goodsTransfer.transfer(eq(ownerInv), eq(clientInv), any())).thenReturn(true);
        economy.settleResult = true;

        settlement.execute(event);

        assertThat(event.isCancelled()).isFalse();
        assertThat(economy.settleCalls).isEqualTo(1);
        verify(goodsTransfer, never()).reverse(any());
    }

    @Test
    void execute_reversesGoods_whenSettlementFails() {
        Player client = player("Notch", UUID.randomUUID());
        Account owner = new Account("Alice", "Alice", UUID.randomUUID());
        Inventory ownerInv = mock(Inventory.class);
        Inventory clientInv = mock(Inventory.class);
        Transaction event = txn(SELL, false, sign(location(mock(World.class))), client, owner, ownerInv, clientInv,
                new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5.00"));
        when(goodsTransfer.transfer(eq(clientInv), eq(ownerInv), any())).thenReturn(true);
        economy.settleResult = false;

        settlement.execute(event);

        assertThat(event.isCancelled()).isTrue();
        verify(goodsTransfer).reverse(event);
    }

    @Test
    void execute_cancelsOnShortfall_whenGoodsDoNotMove() {
        Player client = player("Notch", UUID.randomUUID());
        Account owner = new Account("Alice", "Alice", UUID.randomUUID());
        Inventory ownerInv = mock(Inventory.class);
        Inventory clientInv = mock(Inventory.class);
        Transaction event = txn(BUY, false, sign(location(mock(World.class))), client, owner, ownerInv, clientInv,
                new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5.00"));
        when(goodsTransfer.transfer(any(), any(), any())).thenReturn(false);

        settlement.execute(event);

        assertThat(event.isCancelled()).isTrue();
        assertThat(economy.settleCalls).isZero();
    }

    @Test
    void execute_cancelsOnShortfall_logsWithNullLocation() {
        Player client = player("Notch", UUID.randomUUID());
        Account owner = new Account("Alice", "Alice", UUID.randomUUID());
        Transaction event = txn(BUY, false, null, client, owner, mock(Inventory.class), mock(Inventory.class),
                new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5.00"));
        when(goodsTransfer.transfer(any(), any(), any())).thenReturn(false);

        settlement.execute(event);

        assertThat(event.isCancelled()).isTrue();
    }

    @Test
    void execute_unlimitedAdminShop_movesViaSpawnPath() {
        Player client = player("Notch", UUID.randomUUID());
        Account owner = new Account("Server", "Server", UUID.randomUUID());
        Inventory clientInv = mock(Inventory.class);
        Transaction event = txn(BUY, true, sign(location(mock(World.class))), client, owner, null, clientInv,
                new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5.00"));
        when(goodsTransfer.moveUnlimited(eq(clientInv), any(), eq(true))).thenReturn(true);
        economy.settleResult = true;

        settlement.execute(event);

        assertThat(event.isCancelled()).isFalse();
        verify(goodsTransfer).moveUnlimited(eq(clientInv), any(), eq(true));
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
