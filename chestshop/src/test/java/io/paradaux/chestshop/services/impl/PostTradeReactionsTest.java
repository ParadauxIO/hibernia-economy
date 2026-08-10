package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.services.PostTradeReactions;

import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.ShopService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;
import static io.paradaux.chestshop.model.Transaction.TransactionType.SELL;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for {@link PostTradeReactions}: the ChestShop-internal MONITOR reactions to a settled
 * trade — emptied-shop removal ({@code deleteEmptyShop}), off-thread shop logging
 * ({@code logTransaction}) and buyer/owner trade messaging ({@code sendTransactionMessages} +
 * private {@code sendTradeMessage}). Each method is exercised directly. The economy is a
 * {@link FakeEconomy} hand-double (its {@code bind} signature references Treasury types absent from
 * the test runtime); every other collaborator is a Mockito mock. Split out of the former
 * TransactionServiceImplTest (chestshop/structure/0001).
 */
class PostTradeReactionsTest {

    private FakeEconomy economy;
    private ShopService shops;
    private AccountService accounts;
    private Message message;
    private ItemService items;
    private ChestShopConfiguration config;
    private SignService signService;
    private ShopBlockService shopBlockService;
    private InventoryService inventoryService;

    private PostTradeReactions postTrade;

    @BeforeEach
    void setUp() {
        economy = new FakeEconomy();
        shops = mock(ShopService.class);
        accounts = mock(AccountService.class);
        message = mock(Message.class);
        items = mock(ItemService.class);
        config = mock(ChestShopConfiguration.class);
        signService = mock(SignService.class);
        shopBlockService = mock(ShopBlockService.class);
        inventoryService = mock(InventoryService.class);

        lenient().when(items.getItemList(any())).thenReturn("64 Stone");
        lenient().when(items.getName(any())).thenReturn("Stone");
        lenient().when(message.component(anyString(), any(java.util.Map.class)))
                .thenReturn(mock(net.kyori.adventure.text.Component.class));
        lenient().when(message.component(anyString(), any(Object[].class)))
                .thenReturn(mock(net.kyori.adventure.text.Component.class));

        postTrade = new PostTradeReactionsImpl(economy, shops, accounts, message, items, config, signService,
                shopBlockService, inventoryService);
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

    private Account owner() { return new Account("Alice", "Alice", UUID.randomUUID()); }

    /** A BUY transaction whose owner chest is {@code ownerInv}, for deleteEmptyShop scenarios. */
    private Transaction deletableBuy(Sign s, Inventory ownerInv, Account owner) {
        return txn(BUY, false, s, player("Notch", UUID.randomUUID()), owner, ownerInv, mock(Inventory.class),
                new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5.00"));
    }

    // ═══════════════════════════ deleteEmptyShop ═══════════════════════════

    @Test
    void deleteEmptyShop_sellTrade_isNoOp() {
        Sign s = sign(location(mock(World.class)));
        Account owner = new Account("Alice", "Alice", UUID.randomUUID());
        Transaction event = txn(SELL, false, s, player("Notch", UUID.randomUUID()), owner,
                mock(Inventory.class), mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));

        postTrade.deleteEmptyShop(event);

        verify(shops, never()).onDestroyed(any());
    }

    @Test
    void deleteEmptyShop_adminShop_isNoOp() {
        Sign s = sign(location(mock(World.class)));
        Account owner = new Account("Server", "Server", UUID.randomUUID());
        Transaction event = deletableBuy(s, mock(Inventory.class), owner);
        when(signService.isAdminShop(s)).thenReturn(true); // admin shops are never removed

        postTrade.deleteEmptyShop(event);

        verify(shops, never()).onDestroyed(any());
    }

    @Test
    void deleteEmptyShop_notRemoved_whenShopStillHasStock() {
        Sign s = sign(location(mock(World.class)));
        Inventory ownerInv = mock(Inventory.class);
        Account owner = new Account("Alice", "Alice", UUID.randomUUID());
        Transaction event = deletableBuy(s, ownerInv, owner);
        when(signService.isAdminShop(s)).thenReturn(false);
        when(config.isRemoveEmptyShops()).thenReturn(true);
        when(config.isAllowPartialTransactions()).thenReturn(true);
        when(ownerInv.containsAtLeast(any(), eq(1))).thenReturn(true); // still has stock → keep

        postTrade.deleteEmptyShop(event);

        verify(shops, never()).onDestroyed(any());
    }

    @Test
    void deleteEmptyShop_removesShop_andEmptiesChest() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Sign s = sign(location(world));
        when(s.getType()).thenReturn(Material.OAK_SIGN);
        Block signBlock = mock(Block.class);
        when(s.getBlock()).thenReturn(signBlock);
        Inventory ownerInv = mock(Inventory.class);
        when(ownerInv.getStorageContents()).thenReturn(new ItemStack[]{null}); // empty chest
        Account owner = new Account("Alice", "Alice", UUID.randomUUID());
        Transaction event = deletableBuy(s, ownerInv, owner);
        when(signService.isAdminShop(s)).thenReturn(false);
        when(config.isRemoveEmptyShops()).thenReturn(true);
        when(config.isAllowPartialTransactions()).thenReturn(true);
        when(ownerInv.containsAtLeast(any(), eq(1))).thenReturn(false); // out of stock
        when(config.getRemoveEmptyWorlds()).thenReturn(java.util.Collections.emptySet());
        when(config.isRemoveEmptyChests()).thenReturn(true);
        Container container = mock(Container.class);
        Block containerBlock = mock(Block.class);
        when(container.getBlock()).thenReturn(containerBlock);
        when(shopBlockService.findConnectedContainer(s)).thenReturn(container);

        postTrade.deleteEmptyShop(event);

        verify(shops).onDestroyed(any());
        verify(signBlock).setType(Material.AIR);
        verify(containerBlock).setType(Material.AIR);
    }

    // NOTE: deleteEmptyShop's keep-chest branch (config REMOVE_EMPTY_CHESTS=false) calls
    // Material#isItem() and `new ItemStack(signType, 1)`, both of which require the live Bukkit
    // RegistryAccess ("No RegistryAccess implementation found" headless). That branch — the
    // `!signType.isItem()` / WALL_ normalisation / addItem / warn block — is therefore
    // unreachable in a headless unit test and is the sole uncovered region of this class.

    @Test
    void deleteEmptyShop_notRemoved_whenShopHasStock_partialDisabled() {
        Sign s = sign(location(mock(World.class)));
        Inventory ownerInv = mock(Inventory.class);
        Account owner = new Account("Alice", "Alice", UUID.randomUUID());
        Transaction event = deletableBuy(s, ownerInv, owner);
        when(signService.isAdminShop(s)).thenReturn(false);
        when(config.isRemoveEmptyShops()).thenReturn(true);
        when(config.isAllowPartialTransactions()).thenReturn(false);
        when(inventoryService.hasItems(any(), eq(ownerInv))).thenReturn(true); // still has stock → keep

        postTrade.deleteEmptyShop(event);

        verify(shops, never()).onDestroyed(any());
    }

    @Test
    void deleteEmptyShop_notInRemoveWorld_isNoOp() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("nether");
        Sign s = sign(location(world));
        Inventory ownerInv = mock(Inventory.class);
        Account owner = new Account("Alice", "Alice", UUID.randomUUID());
        Transaction event = deletableBuy(s, ownerInv, owner);
        when(signService.isAdminShop(s)).thenReturn(false);
        when(config.isRemoveEmptyShops()).thenReturn(true);
        when(config.isAllowPartialTransactions()).thenReturn(false);
        when(inventoryService.hasItems(any(), eq(ownerInv))).thenReturn(false);
        when(config.getRemoveEmptyWorlds()).thenReturn(java.util.Collections.singleton("world")); // not "nether"

        postTrade.deleteEmptyShop(event);

        verify(shops, never()).onDestroyed(any());
    }

    @Test
    void deleteEmptyShop_removesShop_worldInRemoveList_emptyChest() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Sign s = sign(location(world));
        when(s.getType()).thenReturn(Material.OAK_SIGN);
        Block signBlock = mock(Block.class);
        when(s.getBlock()).thenReturn(signBlock);
        Inventory ownerInv = mock(Inventory.class);
        when(ownerInv.getStorageContents()).thenReturn(new ItemStack[]{null});
        Account owner = new Account("Alice", "Alice", UUID.randomUUID());
        Transaction event = deletableBuy(s, ownerInv, owner);
        when(signService.isAdminShop(s)).thenReturn(false);
        when(config.isRemoveEmptyShops()).thenReturn(true);
        when(config.isAllowPartialTransactions()).thenReturn(true);
        when(ownerInv.containsAtLeast(any(), eq(1))).thenReturn(false);
        when(config.getRemoveEmptyWorlds()).thenReturn(java.util.Collections.singleton("world")); // world listed → contains true
        when(config.isRemoveEmptyChests()).thenReturn(true);
        Container container = mock(Container.class);
        when(container.getBlock()).thenReturn(mock(Block.class));
        when(shopBlockService.findConnectedContainer(s)).thenReturn(container);

        postTrade.deleteEmptyShop(event);

        verify(shops).onDestroyed(any());
    }

    @Test
    void deleteEmptyShop_removesShop_emptyChest_butNoConnectedContainer() {
        // covers the `container != null` false arc when clearing an empty chest.
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Sign s = sign(location(world));
        when(s.getType()).thenReturn(Material.OAK_SIGN);
        when(s.getBlock()).thenReturn(mock(Block.class));
        Inventory ownerInv = mock(Inventory.class);
        when(ownerInv.getStorageContents()).thenReturn(new ItemStack[]{null});
        Account owner = new Account("Alice", "Alice", UUID.randomUUID());
        Transaction event = deletableBuy(s, ownerInv, owner);
        when(signService.isAdminShop(s)).thenReturn(false);
        when(config.isRemoveEmptyShops()).thenReturn(true);
        when(config.isAllowPartialTransactions()).thenReturn(true);
        when(ownerInv.containsAtLeast(any(), eq(1))).thenReturn(false);
        when(config.getRemoveEmptyWorlds()).thenReturn(java.util.Collections.emptySet());
        when(config.isRemoveEmptyChests()).thenReturn(true);
        when(shopBlockService.findConnectedContainer(s)).thenReturn(null); // no container

        postTrade.deleteEmptyShop(event);

        verify(shops).onDestroyed(any());
    }

    @Test
    void deleteEmptyShop_removeEmptyShopsDisabled_isNoOp() {
        // config.isRemoveEmptyShops() defaults false → shopShouldBeRemoved short-circuits to false.
        Sign s = sign(location(mock(World.class)));
        Inventory ownerInv = mock(Inventory.class);
        Account owner = new Account("Alice", "Alice", UUID.randomUUID());
        Transaction event = deletableBuy(s, ownerInv, owner);
        when(signService.isAdminShop(s)).thenReturn(false);

        postTrade.deleteEmptyShop(event);

        verify(shops, never()).onDestroyed(any());
    }

    // ═══════════════════════════ logTransaction ═══════════════════════════

    @Test
    void logTransaction_writesShopLog_offThread() {
        try (MockedStatic<ChestShop> cs = mockStatic(ChestShop.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            Sign s = sign(location(world));
            Account owner = new Account("Alice", "Alice", UUID.randomUUID());
            Transaction event = txn(BUY, false, s, player("Notch", UUID.randomUUID()), owner,
                    mock(Inventory.class), mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5.00"));
            Map<ItemStack, Integer> counts = new LinkedHashMap<>();
            counts.put(item(Material.STONE, 1), 1);
            when(inventoryService.getItemCounts(any())).thenReturn(counts);
            // Execute the async logging runnable inline so its body (the log line) is covered.
            cs.when(() -> ChestShop.runInAsyncThread(any())).thenAnswer(inv -> {
                ((Runnable) inv.getArgument(0)).run();
                return null;
            });

            postTrade.logTransaction(event);

            cs.verify(() -> ChestShop.runInAsyncThread(any()));
        }
    }

    @Test
    void logTransaction_sellTemplate() {
        try (MockedStatic<ChestShop> cs = mockStatic(ChestShop.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            Sign s = sign(location(world));
            Account owner = new Account("Alice", "Alice", UUID.randomUUID());
            Transaction event = txn(SELL, false, s, player("Notch", UUID.randomUUID()), owner,
                    mock(Inventory.class), mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5.00"));
            Map<ItemStack, Integer> counts = new LinkedHashMap<>();
            counts.put(item(Material.STONE, 1), 1);
            when(inventoryService.getItemCounts(any())).thenReturn(counts);
            cs.when(() -> ChestShop.runInAsyncThread(any())).thenAnswer(inv -> {
                ((Runnable) inv.getArgument(0)).run();
                return null;
            });

            postTrade.logTransaction(event);

            cs.verify(() -> ChestShop.runInAsyncThread(any()));
        }
    }

    // ═══════════════════════════ sendTransactionMessages / sendTradeMessage ═══════════════════════════

    @Test
    void sendTransactionMessages_notifiesClientAndOnlineOwner() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            UUID ownerId = UUID.randomUUID();
            Player client = player("Notch", UUID.randomUUID());
            Account owner = new Account("Alice", "Alice", ownerId);
            Player ownerPlayer = player("Alice", ownerId);
            Sign s = sign(location(world));
            Transaction event = txn(BUY, false, s, client, owner, mock(Inventory.class), mock(Inventory.class),
                    new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5.00"));
            when(config.isShowTransactionInformationClient()).thenReturn(true);
            when(config.isShowTransactionInformationOwner()).thenReturn(true);
            when(accounts.isIgnoring(ownerId)).thenReturn(false);
            bk.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(ownerPlayer);

            postTrade.sendTransactionMessages(event);

            verify(client).sendMessage(any(net.kyori.adventure.text.Component.class));
            verify(ownerPlayer).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }

    @Test
    void sendTransactionMessages_ownerMessageSkipped_whenIgnoring() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            UUID ownerId = UUID.randomUUID();
            Player client = player("Notch", UUID.randomUUID());
            Account owner = new Account("Alice", "Alice", ownerId);
            Sign s = sign(location(world));
            Transaction event = txn(BUY, false, s, client, owner, mock(Inventory.class), mock(Inventory.class),
                    new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5.00"));
            when(config.isShowTransactionInformationClient()).thenReturn(true);
            when(config.isShowTransactionInformationOwner()).thenReturn(true);
            when(accounts.isIgnoring(ownerId)).thenReturn(true); // owner muted → skip owner notify

            postTrade.sendTransactionMessages(event);

            verify(client).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }

    @Test
    void sendTransactionMessages_ownerOffline_tradeMessageReturnsEarly() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            UUID ownerId = UUID.randomUUID();
            Player client = player("Notch", UUID.randomUUID());
            Account owner = new Account("Alice", "Alice", ownerId);
            Sign s = sign(location(world));
            Transaction event = txn(BUY, false, s, client, owner, mock(Inventory.class), mock(Inventory.class),
                    new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5.00"));
            when(config.isShowTransactionInformationClient()).thenReturn(false); // skip client branch
            when(config.isShowTransactionInformationOwner()).thenReturn(true);
            when(accounts.isIgnoring(ownerId)).thenReturn(false);
            bk.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(null); // owner offline → sendTradeMessage returns

            postTrade.sendTransactionMessages(event);

            verify(client, never()).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }

    @Test
    void sendTransactionMessages_sellTrade_notifiesBothParties() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            UUID ownerId = UUID.randomUUID();
            Player client = player("Notch", UUID.randomUUID());
            Account owner = new Account("Alice", "Alice", ownerId);
            Player ownerPlayer = player("Alice", ownerId);
            Sign s = sign(location(world));
            Transaction event = txn(SELL, false, s, client, owner, mock(Inventory.class), mock(Inventory.class),
                    new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5.00"));
            when(config.isShowTransactionInformationClient()).thenReturn(true);
            when(config.isShowTransactionInformationOwner()).thenReturn(true);
            when(accounts.isIgnoring(ownerId)).thenReturn(false);
            bk.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(ownerPlayer);

            postTrade.sendTransactionMessages(event);

            verify(client).sendMessage(any(net.kyori.adventure.text.Component.class));
            verify(ownerPlayer).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }

    @Test
    void sendTransactionMessages_bothDisabled_notifiesNobody() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            lenient().when(world.getName()).thenReturn("world");
            UUID ownerId = UUID.randomUUID();
            Player client = player("Notch", UUID.randomUUID());
            Account owner = new Account("Alice", "Alice", ownerId);
            Sign s = sign(location(world));
            Transaction event = txn(BUY, false, s, client, owner, mock(Inventory.class), mock(Inventory.class),
                    new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5.00"));
            // both config flags default false → neither the client nor the owner branch fires.

            postTrade.sendTransactionMessages(event);

            verify(client, never()).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }

    @Test
    void sendTradeMessage_usesPlaceholderForUnloadedWorld() throws Exception {
        // sendTradeMessage's world-null guard (ADT-140) is unreachable through sendTransactionMessages()
        // because logTransaction dereferences the world first; exercise it directly.
        Player recipient = player("Alice", UUID.randomUUID());
        Sign s = sign(location(null)); // unloaded world
        Account owner = new Account("Alice", "Alice", UUID.randomUUID());
        Transaction event = txn(BUY, false, s, player("Notch", UUID.randomUUID()), owner,
                mock(Inventory.class), mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));

        var m = PostTradeReactionsImpl.class.getDeclaredMethod(
                "sendTradeMessage", Player.class, String.class, Transaction.class, String[].class);
        m.setAccessible(true);
        m.invoke(postTrade, recipient, "chestshop.YOU_BOUGHT_FROM_SHOP", event, new String[]{"owner", "Alice"});

        verify(recipient).sendMessage(any(net.kyori.adventure.text.Component.class));
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
