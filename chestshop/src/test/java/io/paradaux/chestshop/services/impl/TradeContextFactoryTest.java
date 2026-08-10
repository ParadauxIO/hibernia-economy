package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.services.TradeContextFactory;

import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;

import java.util.UUID;

import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;
import static io.paradaux.chestshop.model.Transaction.TransactionType.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Trade-context construction coverage for {@link TradeContextFactory#prepare}: owner resolution,
 * pricing, shift-selling (in-stacks / everything), and unlimited admin shops. The economy is a
 * {@link FakeEconomy} hand-double (its {@code bind} signature references Treasury types absent from
 * the test runtime); every other collaborator is a Mockito mock. Static {@link SignService} calls
 * are stubbed per test. Split out of the former TransactionServiceImplTest (chestshop/structure/0001).
 */
class TradeContextFactoryTest {

    private FakeEconomy economy;
    private AccountService accounts;
    private Message message;
    private ItemService items;
    private ChestShopConfiguration config;
    private SignService signService;
    private ShopBlockService shopBlockService;
    private InventoryService inventoryService;

    private TradeContextFactory factory;

    @BeforeEach
    void setUp() {
        economy = new FakeEconomy();
        accounts = mock(AccountService.class);
        message = mock(Message.class);
        items = mock(ItemService.class);
        config = mock(ChestShopConfiguration.class);
        signService = mock(SignService.class);
        shopBlockService = mock(ShopBlockService.class);
        inventoryService = mock(InventoryService.class);

        lenient().when(config.getMaxShopAmount()).thenReturn(3840);
        lenient().when(items.getItemList(any())).thenReturn("64 Stone");
        lenient().when(items.getName(any())).thenReturn("Stone");
        lenient().when(message.component(anyString(), any(java.util.Map.class)))
                .thenReturn(mock(net.kyori.adventure.text.Component.class));
        lenient().when(message.component(anyString(), any(Object[].class)))
                .thenReturn(mock(net.kyori.adventure.text.Component.class));

        factory = new TradeContextFactoryImpl(economy, accounts, message, items, config, signService,
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

    private Sign prepSign() {
        return sign(location(mock(World.class)));
    }

    private void prepBasics(MockedStatic<SignService> ss, Sign s, String owner, String price, boolean admin) {
        ss.when(() -> SignService.getOwner(s)).thenReturn(owner);
        ss.when(() -> SignService.getPrice(s)).thenReturn(price);
        ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
        ss.when(() -> SignService.getQuantity(s)).thenReturn(1);
        when(accounts.resolveAccount(owner)).thenReturn(new Account(owner, owner, UUID.randomUUID()));
        when(signService.isAdminShop(s)).thenReturn(admin);
        economy.hasAccount = true;
    }

    // ═══════════════════════════ prepare ═══════════════════════════

    @Test
    void prepare_playerNotFound_returnsNull() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Ghost");
            when(accounts.resolveAccount("Ghost")).thenReturn(null);
            assertThat(factory.prepare(s, player("Notch", UUID.randomUUID()), Action.RIGHT_CLICK_BLOCK)).isNull();
            verify(message).send(any(org.bukkit.command.CommandSender.class), eq("chestshop.PLAYER_NOT_FOUND"));
        }
    }

    @Test
    void prepare_noEconomyAccount_returnsNull() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Alice");
            Account acc = new Account("Alice", "Alice", UUID.randomUUID());
            when(accounts.resolveAccount("Alice")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(false);
            economy.hasAccount = false;
            assertThat(factory.prepare(s, player("Notch", UUID.randomUUID()), Action.RIGHT_CLICK_BLOCK)).isNull();
            verify(message).send(any(org.bukkit.command.CommandSender.class), eq("chestshop.NO_ECONOMY_ACCOUNT"));
        }
    }

    @Test
    void prepare_invalidItem_returnsNull() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Alice");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("NOPE");
            Account acc = new Account("Alice", "Alice", UUID.randomUUID());
            when(accounts.resolveAccount("Alice")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(false);
            economy.hasAccount = true;
            when(items.parse("NOPE")).thenReturn(null);
            assertThat(factory.prepare(s, player("Notch", UUID.randomUUID()), Action.RIGHT_CLICK_BLOCK)).isNull();
            verify(message).send(any(org.bukkit.command.CommandSender.class), eq("chestshop.INVALID_SHOP_DETECTED"));
        }
    }

    @Test
    void prepare_quantityUnparseable_returnsNull() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Alice");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenThrow(new NumberFormatException("nan"));
            Account acc = new Account("Alice", "Alice", UUID.randomUUID());
            when(accounts.resolveAccount("Alice")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(false);
            economy.hasAccount = true;
            when(items.parse("STONE")).thenReturn(item(Material.STONE, 1));
            when(shopBlockService.findConnectedContainer(s)).thenReturn(mock(Container.class));
            assertThat(factory.prepare(s, player("Notch", UUID.randomUUID()), Action.RIGHT_CLICK_BLOCK)).isNull();
            verify(message).send(any(org.bukkit.command.CommandSender.class), eq("chestshop.INVALID_SHOP_PRICE"));
        }
    }

    @Test
    void prepare_quantityTooLarge_returnsNull() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Alice");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenReturn(999999); // > maxShopAmount
            Account acc = new Account("Alice", "Alice", UUID.randomUUID());
            when(accounts.resolveAccount("Alice")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(false);
            economy.hasAccount = true;
            when(items.parse("STONE")).thenReturn(item(Material.STONE, 1));
            when(shopBlockService.findConnectedContainer(s)).thenReturn(mock(Container.class));
            assertThat(factory.prepare(s, player("Notch", UUID.randomUUID()), Action.RIGHT_CLICK_BLOCK)).isNull();
        }
    }

    @Test
    void prepare_normalBuy_buildsContext() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Alice");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenReturn(1);
            Account acc = new Account("Alice", "Alice", UUID.randomUUID());
            when(accounts.resolveAccount("Alice")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(false);
            economy.hasAccount = true;
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            Container container = mock(Container.class);
            Inventory ownerInv = mock(Inventory.class);
            when(container.getInventory()).thenReturn(ownerInv);
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, player("Notch", UUID.randomUUID()), Action.RIGHT_CLICK_BLOCK);

            assertThat(ctx).isNotNull();
            assertThat(ctx.getTransactionType()).isEqualTo(BUY);
            assertThat(ctx.getExactPrice()).isEqualByComparingTo("5");
            assertThat(ctx.isUnlimitedOwner()).isFalse();
        }
    }

    @Test
    void prepare_sellWithReverseButtons_buildsSellContext() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Alice");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenReturn(1);
            Account acc = new Account("Alice", "Alice", UUID.randomUUID());
            when(accounts.resolveAccount("Alice")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(false);
            economy.hasAccount = true;
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            when(config.isReverseButtons()).thenReturn(true); // buy is LEFT; RIGHT_CLICK becomes SELL
            Container container = mock(Container.class);
            when(container.getInventory()).thenReturn(mock(Inventory.class));
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, player("Notch", UUID.randomUUID()), Action.RIGHT_CLICK_BLOCK);

            assertThat(ctx.getTransactionType()).isEqualTo(SELL);
            assertThat(ctx.getExactPrice()).isEqualByComparingTo("3");
        }
    }

    @Test
    void prepare_unlimitedAdminShop_noContainer() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Server");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenReturn(1);
            Account acc = new Account("Server", "Server", UUID.randomUUID());
            when(accounts.resolveAccount("Server")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(true);
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            when(shopBlockService.findConnectedContainer(s)).thenReturn(null); // no chest
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, player("Notch", UUID.randomUUID()), Action.RIGHT_CLICK_BLOCK);

            assertThat(ctx.isUnlimitedOwner()).isTrue();
        }
    }

    @Test
    void prepare_forcedUnlimitedAdminShop_withContainer() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Server");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenReturn(1);
            Account acc = new Account("Server", "Server", UUID.randomUUID());
            when(accounts.resolveAccount("Server")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(true);
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            Container container = mock(Container.class);
            when(container.getInventory()).thenReturn(mock(Inventory.class));
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            when(config.isForceUnlimitedAdminShop()).thenReturn(true);
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, player("Notch", UUID.randomUUID()), Action.RIGHT_CLICK_BLOCK);

            assertThat(ctx.isUnlimitedOwner()).isTrue();
        }
    }

    // ── shift-selling in prepare ──

    @Test
    void prepare_shiftSellsInStacks_adminShop_usesMaxStackSize() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Server");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenReturn(1);
            Account acc = new Account("Server", "Server", UUID.randomUUID());
            when(accounts.resolveAccount("Server")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(true);
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            when(shopBlockService.findConnectedContainer(s)).thenReturn(null);
            Player p = player("Notch", UUID.randomUUID());
            when(p.isSneaking()).thenReturn(true);
            when(config.isShiftSellsInStacks()).thenReturn(true);
            when(config.getShiftAllows()).thenReturn("ALL");
            when(inventoryService.getMaxStackSize(parsed)).thenReturn(64);
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, p, Action.RIGHT_CLICK_BLOCK);

            assertThat(ctx).isNotNull();
            assertThat(ctx.getExactPrice()).isEqualByComparingTo("320"); // 5/item * 64
        }
    }

    @Test
    void prepare_shiftSellsInStacks_playerShop_usesStackAmount() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Alice");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenReturn(1);
            Account acc = new Account("Alice", "Alice", UUID.randomUUID());
            when(accounts.resolveAccount("Alice")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(false);
            economy.hasAccount = true;
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            Container container = mock(Container.class);
            Inventory ownerInv = mock(Inventory.class);
            when(container.getInventory()).thenReturn(ownerInv);
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            Player p = player("Notch", UUID.randomUUID());
            when(p.isSneaking()).thenReturn(true);
            when(config.isShiftSellsInStacks()).thenReturn(true);
            when(config.getShiftAllows()).thenReturn("BUY");
            when(ownerInv.containsAtLeast(eq(parsed), anyInt())).thenReturn(false);
            when(inventoryService.getMaxStackSize(parsed)).thenReturn(64);
            when(inventoryService.getAmount(parsed, ownerInv)).thenReturn(10);
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, p, Action.RIGHT_CLICK_BLOCK);

            assertThat(ctx.getExactPrice()).isEqualByComparingTo("50"); // 5 * 10
        }
    }

    @Test
    void prepare_shiftSellsEverything_sellUsesPlayerInventory() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Alice");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenReturn(1);
            Account acc = new Account("Alice", "Alice", UUID.randomUUID());
            when(accounts.resolveAccount("Alice")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(false);
            economy.hasAccount = true;
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            Container container = mock(Container.class);
            when(container.getInventory()).thenReturn(mock(Inventory.class));
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            Player p = player("Notch", UUID.randomUUID());
            PlayerInventory pInv = (PlayerInventory) p.getInventory();
            when(p.isSneaking()).thenReturn(true);
            when(config.isShiftSellsInStacks()).thenReturn(false);
            when(config.isShiftSellsEverything()).thenReturn(true);
            when(config.getShiftAllows()).thenReturn("SELL");
            when(inventoryService.getAmount(parsed, pInv)).thenReturn(7);
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, p, Action.LEFT_CLICK_BLOCK);

            assertThat(ctx.getTransactionType()).isEqualTo(SELL);
            assertThat(ctx.getExactPrice()).isEqualByComparingTo("21"); // 3 * 7
        }
    }

    @Test
    void prepare_shiftSellsEverything_buyUsesOwnerInventory() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Alice");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenReturn(1);
            Account acc = new Account("Alice", "Alice", UUID.randomUUID());
            when(accounts.resolveAccount("Alice")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(false);
            economy.hasAccount = true;
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            Container container = mock(Container.class);
            Inventory ownerInv = mock(Inventory.class);
            when(container.getInventory()).thenReturn(ownerInv);
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            Player p = player("Notch", UUID.randomUUID());
            when(p.isSneaking()).thenReturn(true);
            when(config.isShiftSellsInStacks()).thenReturn(false);
            when(config.isShiftSellsEverything()).thenReturn(true);
            when(config.getShiftAllows()).thenReturn("BUY");
            when(inventoryService.getAmount(parsed, ownerInv)).thenReturn(9);
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, p, Action.RIGHT_CLICK_BLOCK);

            assertThat(ctx.getTransactionType()).isEqualTo(BUY);
            assertThat(ctx.getExactPrice()).isEqualByComparingTo("45"); // 5 * 9
        }
    }

    @Test
    void prepare_shiftSellsInStacks_notAllowedForDirection_skipsShift() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Alice");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenReturn(1);
            Account acc = new Account("Alice", "Alice", UUID.randomUUID());
            when(accounts.resolveAccount("Alice")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(false);
            economy.hasAccount = true;
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            Container container = mock(Container.class);
            when(container.getInventory()).thenReturn(mock(Inventory.class));
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            Player p = player("Notch", UUID.randomUUID());
            when(p.isSneaking()).thenReturn(true);
            when(config.isShiftSellsInStacks()).thenReturn(true);
            when(config.getShiftAllows()).thenReturn("SELL"); // buy click, but only SELL allowed → skip shift
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, p, Action.RIGHT_CLICK_BLOCK);

            assertThat(ctx.getExactPrice()).isEqualByComparingTo("5"); // unchanged (no shift)
        }
    }

    @Test
    void prepare_getStackAmount_fullStackAvailable_returnsMaxStackSize() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Alice");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenReturn(1);
            Account acc = new Account("Alice", "Alice", UUID.randomUUID());
            when(accounts.resolveAccount("Alice")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(false);
            economy.hasAccount = true;
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            Container container = mock(Container.class);
            Inventory ownerInv = mock(Inventory.class);
            when(container.getInventory()).thenReturn(ownerInv);
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            Player p = player("Notch", UUID.randomUUID());
            when(p.isSneaking()).thenReturn(true);
            when(config.isShiftSellsInStacks()).thenReturn(true);
            when(config.getShiftAllows()).thenReturn("ALL");
            when(inventoryService.getMaxStackSize(parsed)).thenReturn(64);
            when(ownerInv.containsAtLeast(parsed, 64)).thenReturn(true); // a full stack is present
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, p, Action.RIGHT_CLICK_BLOCK);

            assertThat(ctx.getExactPrice()).isEqualByComparingTo("320"); // 5 * 64
        }
    }

    @Test
    void prepare_adminShopWithContainer_notForced_isNotUnlimited() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Server");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenReturn(1);
            Account acc = new Account("Server", "Server", UUID.randomUUID());
            when(accounts.resolveAccount("Server")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(true);
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            Container container = mock(Container.class);
            when(container.getInventory()).thenReturn(mock(Inventory.class));
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            when(config.isForceUnlimitedAdminShop()).thenReturn(false);
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, player("Notch", UUID.randomUUID()), Action.RIGHT_CLICK_BLOCK);

            assertThat(ctx.isUnlimitedOwner()).isFalse();
        }
    }

    @Test
    void prepare_shiftSellsInStacks_sellDirection_usesPlayerInventory() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Alice");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenReturn(1);
            Account acc = new Account("Alice", "Alice", UUID.randomUUID());
            when(accounts.resolveAccount("Alice")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(false);
            economy.hasAccount = true;
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            Container container = mock(Container.class);
            when(container.getInventory()).thenReturn(mock(Inventory.class));
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            Player p = player("Notch", UUID.randomUUID());
            PlayerInventory pInv = (PlayerInventory) p.getInventory();
            when(p.isSneaking()).thenReturn(true);
            when(config.isShiftSellsInStacks()).thenReturn(true);
            when(config.getShiftAllows()).thenReturn("ALL");
            when(inventoryService.getMaxStackSize(parsed)).thenReturn(64);
            when(pInv.containsAtLeast(parsed, 64)).thenReturn(false);
            when(inventoryService.getAmount(parsed, pInv)).thenReturn(5);
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, p, Action.LEFT_CLICK_BLOCK); // SELL

            assertThat(ctx.getTransactionType()).isEqualTo(SELL);
            assertThat(ctx.getExactPrice()).isEqualByComparingTo("15"); // 3 * 5
        }
    }

    @Test
    void prepare_shiftSellsEverything_zeroAmount_leavesQuantityUnchanged() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Alice");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenReturn(1);
            Account acc = new Account("Alice", "Alice", UUID.randomUUID());
            when(accounts.resolveAccount("Alice")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(false);
            economy.hasAccount = true;
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            Container container = mock(Container.class);
            when(container.getInventory()).thenReturn(mock(Inventory.class));
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            Player p = player("Notch", UUID.randomUUID());
            PlayerInventory pInv = (PlayerInventory) p.getInventory();
            when(p.isSneaking()).thenReturn(true);
            when(config.isShiftSellsEverything()).thenReturn(true);
            when(config.getShiftAllows()).thenReturn("ALL");
            when(inventoryService.getAmount(parsed, pInv)).thenReturn(0); // nothing to sell → newAmount 0 → unchanged

            PendingTransaction ctx = factory.prepare(s, p, Action.LEFT_CLICK_BLOCK);

            assertThat(ctx.getExactPrice()).isEqualByComparingTo("3"); // unchanged
        }
    }

    @Test
    void prepare_shiftSellsEverything_buyAdminShop_skipsOwnerBranch() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Server");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenReturn(1);
            Account acc = new Account("Server", "Server", UUID.randomUUID());
            when(accounts.resolveAccount("Server")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(true);
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            when(shopBlockService.findConnectedContainer(s)).thenReturn(null); // admin, no container
            Player p = player("Notch", UUID.randomUUID());
            when(p.isSneaking()).thenReturn(true);
            when(config.isShiftSellsEverything()).thenReturn(true);
            when(config.getShiftAllows()).thenReturn("ALL");
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, p, Action.RIGHT_CLICK_BLOCK);

            assertThat(ctx.getExactPrice()).isEqualByComparingTo("5");
        }
    }

    @Test
    void prepare_notSneaking_skipsShift() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            ss.when(() -> SignService.getOwner(s)).thenReturn("Alice");
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s3");
            ss.when(() -> SignService.getItem(s)).thenReturn("STONE");
            ss.when(() -> SignService.getQuantity(s)).thenReturn(1);
            Account acc = new Account("Alice", "Alice", UUID.randomUUID());
            when(accounts.resolveAccount("Alice")).thenReturn(acc);
            when(signService.isAdminShop(s)).thenReturn(false);
            economy.hasAccount = true;
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            Container container = mock(Container.class);
            when(container.getInventory()).thenReturn(mock(Inventory.class));
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            Player p = player("Notch", UUID.randomUUID());
            when(p.isSneaking()).thenReturn(false); // not sneaking → both shift blocks skipped
            when(config.isShiftSellsInStacks()).thenReturn(true);
            when(config.isShiftSellsEverything()).thenReturn(true);
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, p, Action.RIGHT_CLICK_BLOCK);

            assertThat(ctx.getExactPrice()).isEqualByComparingTo("5");
        }
    }

    @Test
    void prepare_shiftEnabled_butNoPriceForDirection_skipsShift() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            prepBasics(ss, s, "Alice", "s3", false); // buy price absent → getExactBuyPrice == NO_PRICE
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            Container container = mock(Container.class);
            when(container.getInventory()).thenReturn(mock(Inventory.class));
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            Player p = player("Notch", UUID.randomUUID());
            when(p.isSneaking()).thenReturn(true);
            when(config.isShiftSellsInStacks()).thenReturn(true);
            when(config.isShiftSellsEverything()).thenReturn(true);
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, p, Action.RIGHT_CLICK_BLOCK); // BUY with no buy price

            assertThat(ctx.getExactPrice()).isEqualByComparingTo("-1"); // NO_PRICE, shift skipped
        }
    }

    @Test
    void prepare_shiftEverything_buy_nonAdminNullChest_skipsOwnerBranch() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            prepBasics(ss, s, "Alice", "b5:s3", false);
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            when(shopBlockService.findConnectedContainer(s)).thenReturn(null); // non-admin but no chest → ownerInv null
            Player p = player("Notch", UUID.randomUUID());
            when(p.isSneaking()).thenReturn(true);
            when(config.isShiftSellsEverything()).thenReturn(true);
            when(config.getShiftAllows()).thenReturn("ALL");
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, p, Action.RIGHT_CLICK_BLOCK);

            assertThat(ctx.getExactPrice()).isEqualByComparingTo("5"); // owner branch skipped (ownerInv null)
        }
    }

    @Test
    void prepare_shiftEverything_buy_ownerChestEmpty_leavesUnchanged() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            prepBasics(ss, s, "Alice", "b5:s3", false);
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            Container container = mock(Container.class);
            Inventory ownerInv = mock(Inventory.class);
            when(container.getInventory()).thenReturn(ownerInv);
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            Player p = player("Notch", UUID.randomUUID());
            when(p.isSneaking()).thenReturn(true);
            when(config.isShiftSellsEverything()).thenReturn(true);
            when(config.getShiftAllows()).thenReturn("ALL");
            when(inventoryService.getAmount(parsed, ownerInv)).thenReturn(0); // empty chest → newAmount 0 → unchanged
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, p, Action.RIGHT_CLICK_BLOCK);

            assertThat(ctx.getExactPrice()).isEqualByComparingTo("5");
        }
    }

    @Test
    void prepare_getStackAmount_withReverseButtons() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            prepBasics(ss, s, "Alice", "b5:s3", false);
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            Container container = mock(Container.class);
            Inventory ownerInv = mock(Inventory.class);
            when(container.getInventory()).thenReturn(ownerInv);
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            Player p = player("Notch", UUID.randomUUID());
            when(p.isSneaking()).thenReturn(true);
            when(config.isReverseButtons()).thenReturn(true); // buy is LEFT
            when(config.isShiftSellsInStacks()).thenReturn(true);
            when(config.getShiftAllows()).thenReturn("ALL");
            when(inventoryService.getMaxStackSize(parsed)).thenReturn(64);
            when(ownerInv.containsAtLeast(parsed, 64)).thenReturn(false);
            when(inventoryService.getAmount(parsed, ownerInv)).thenReturn(3);
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, p, Action.LEFT_CLICK_BLOCK); // BUY (reversed)

            assertThat(ctx.getTransactionType()).isEqualTo(BUY);
            assertThat(ctx.getExactPrice()).isEqualByComparingTo("15"); // 5 * 3
        }
    }

    @Test
    void prepare_shiftInStacks_zeroStackAmount_leavesUnchanged() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            prepBasics(ss, s, "Alice", "b5:s3", false);
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            Container container = mock(Container.class);
            Inventory ownerInv = mock(Inventory.class);
            when(container.getInventory()).thenReturn(ownerInv);
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            Player p = player("Notch", UUID.randomUUID());
            when(p.isSneaking()).thenReturn(true);
            when(config.isShiftSellsInStacks()).thenReturn(true);
            when(config.getShiftAllows()).thenReturn("ALL");
            when(inventoryService.getMaxStackSize(parsed)).thenReturn(64);
            when(ownerInv.containsAtLeast(parsed, 64)).thenReturn(false);
            when(inventoryService.getAmount(parsed, ownerInv)).thenReturn(0); // getStackAmount → 0 → newAmount not applied
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, p, Action.RIGHT_CLICK_BLOCK);

            assertThat(ctx.getExactPrice()).isEqualByComparingTo("5"); // unchanged
        }
    }

    @Test
    void prepare_shiftEverything_notAllowedForDirection_skips() {
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            Sign s = prepSign();
            prepBasics(ss, s, "Alice", "b5:s3", false);
            ItemStack parsed = item(Material.STONE, 1);
            when(items.parse("STONE")).thenReturn(parsed);
            Container container = mock(Container.class);
            when(container.getInventory()).thenReturn(mock(Inventory.class));
            when(shopBlockService.findConnectedContainer(s)).thenReturn(container);
            Player p = player("Notch", UUID.randomUUID());
            when(p.isSneaking()).thenReturn(true);
            when(config.isShiftSellsEverything()).thenReturn(true);
            when(config.getShiftAllows()).thenReturn("BUY"); // SELL click but only BUY shift allowed → isAllowedForShift false
            when(inventoryService.getItemsStacked(parsed)).thenReturn(new ItemStack[]{parsed});

            PendingTransaction ctx = factory.prepare(s, p, Action.LEFT_CLICK_BLOCK); // SELL

            assertThat(ctx.getExactPrice()).isEqualByComparingTo("3"); // unchanged
        }
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
