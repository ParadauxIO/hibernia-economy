package io.paradaux.chestshop.services.impl;

import io.paradaux.chestshop.services.PartialFillCalculator;
import io.paradaux.chestshop.services.TradeValidator;

import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.PendingTransaction;
import io.paradaux.chestshop.model.PendingTransaction.TransactionOutcome;
import io.paradaux.chestshop.model.Transaction;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.AdminBypassService;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.RestrictedSignService;
import io.paradaux.chestshop.services.SignBreakService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
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
import java.util.UUID;

import static io.paradaux.chestshop.model.Transaction.TransactionType.BUY;
import static io.paradaux.chestshop.model.Transaction.TransactionType.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for {@link TradeValidator}: the ordered pre-trade validation steps, cancellation
 * outcomes, error messaging (client + owner, with cooldowns), free-shop flagging/removal and
 * notification-cooldown clearing. The economy is a {@link FakeEconomy} hand-double (its {@code bind}
 * signature references Treasury types absent from the test runtime); every other collaborator is a
 * Mockito mock. Split out of the former TransactionServiceImplTest (chestshop/structure/0001).
 */
class TradeValidatorTest {

    private FakeEconomy economy;
    private AccountService accounts;
    private SignBreakService signBreak;
    private Message message;
    private ItemService items;
    private ChestShopConfiguration config;
    private SignService signService;
    private InventoryService inventoryService;
    private AdminBypassService adminBypass;
    private RestrictedSignService restrictedSign;
    private PartialFillCalculator partialFill;

    /** Test-driven wall clock for the notification cooldown (deterministic — no Thread.sleep). */
    private final long[] clockNow = {1_000_000L};

    private TradeValidator validator;

    @BeforeEach
    void setUp() {
        economy = new FakeEconomy();
        accounts = mock(AccountService.class);
        signBreak = mock(SignBreakService.class);
        message = mock(Message.class);
        items = mock(ItemService.class);
        config = mock(ChestShopConfiguration.class);
        signService = mock(SignService.class);
        inventoryService = mock(InventoryService.class);
        adminBypass = mock(AdminBypassService.class);
        restrictedSign = mock(RestrictedSignService.class);
        partialFill = mock(PartialFillCalculator.class);

        lenient().when(config.getValidPlayernameRegexp()).thenReturn("^[A-Za-z0-9_]{1,16}$");
        lenient().when(config.getNotificationMessageCooldown()).thenReturn(0L);
        lenient().when(config.getMaxShopAmount()).thenReturn(3840);
        lenient().when(items.getItemList(any())).thenReturn("64 Stone");
        lenient().when(items.getName(any())).thenReturn("Stone");
        lenient().when(message.component(anyString(), any(java.util.Map.class)))
                .thenReturn(mock(net.kyori.adventure.text.Component.class));
        lenient().when(message.component(anyString(), any(Object[].class)))
                .thenReturn(mock(net.kyori.adventure.text.Component.class));

        validator = new TradeValidatorImpl(economy, accounts, signBreak, message, items, config, signService,
                inventoryService, adminBypass, restrictedSign, partialFill, () -> clockNow[0]);
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

    private PendingTransaction pending(Transaction.TransactionType type, boolean unlimited, Sign s,
                                       Player client, Account owner, Inventory ownerInv, ItemStack[] stock, BigDecimal price) {
        return new PendingTransaction(ownerInv, mock(Inventory.class), stock, price, client, owner, s, type, unlimited);
    }

    private Account owner() { return new Account("Alice", "Alice", UUID.randomUUID()); }

    // ═══════════════════════════ clearNotificationCooldowns ═══════════════════════════

    @Test
    void clearNotificationCooldowns_noOpWhenCooldownDisabled() {
        when(config.getNotificationMessageCooldown()).thenReturn(0L);
        validator.clearNotificationCooldowns(UUID.randomUUID()); // must not throw
    }

    @Test
    void clearNotificationCooldowns_removesRows_whenEnabled() {
        when(config.getNotificationMessageCooldown()).thenReturn(5L);
        validator.clearNotificationCooldowns(UUID.randomUUID());
    }

    // ═══════════════════════════ validate — individual validators ═══════════════════════════

    @Test
    void validate_rejectsAdminShopClientName() {
        Player client = player("Server", UUID.randomUUID());
        when(signService.isAdminShop("Server")).thenReturn(true);
        PendingTransaction ctx = pending(BUY, false, sign(location(mock(World.class))), client, owner(),
                mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
        validator.validate(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.INVALID_CLIENT_NAME);
    }

    @Test
    void validate_rejectsInvalidClientNameByRegex() {
        Player client = player("bad name!", UUID.randomUUID());
        when(signService.isAdminShop("bad name!")).thenReturn(false);
        PendingTransaction ctx = pending(BUY, false, sign(location(mock(World.class))), client, owner(),
                mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
        validator.validate(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.INVALID_CLIENT_NAME);
    }

    @Test
    void validate_rejectsCreativeMode() {
        Player client = player("Notch", UUID.randomUUID());
        when(client.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(config.isIgnoreCreativeMode()).thenReturn(true);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getPrice(any(Sign.class))).thenReturn("b5");
            PendingTransaction ctx = pending(BUY, false, sign(location(mock(World.class))), client, owner(),
                    mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
            validator.validate(ctx);
            assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.CREATIVE_MODE_PROTECTION);
        }
    }

    @Test
    void validate_flagsAndRemovesFreeShop() {
        Player client = player("Notch", UUID.randomUUID());
        when(config.isAllowFreeShops()).thenReturn(false);
        Block block = mock(Block.class);
        Sign s = sign(location(mock(World.class)));
        when(s.getBlock()).thenReturn(block);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getPrice(s)).thenReturn("b0"); // free buy price
            PendingTransaction ctx = pending(BUY, false, s, client, owner(),
                    mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
            validator.validate(ctx);
            assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.INVALID_SHOP);
            assertThat(ctx.isRejectedAsFreeShop()).isTrue();
            verify(signBreak).sendShopDestroyed(s, client);
            verify(block).breakNaturally();
        }
    }

    @Test
    void validate_freeShopSkipped_whenAllowFreeShopsOn() {
        Player client = player("Notch", UUID.randomUUID());
        when(config.isAllowFreeShops()).thenReturn(true);
        when(config.isAllowPartialTransactions()).thenReturn(true);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getItem(any(Sign.class))).thenReturn("STONE");
            when(adminBypass.has(any(), any())).thenReturn(true);
            when(inventoryService.fits(any(ItemStack[].class), any())).thenReturn(true);
            PendingTransaction ctx = pending(BUY, true, sign(location(mock(World.class))), client, owner(),
                    mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
            validator.validate(ctx);
            assertThat(ctx.isCancelled()).isFalse();
        }
    }

    @Test
    void validate_freeShop_skippedWhenSignNull() {
        Player client = player("Notch", UUID.randomUUID());
        when(config.isAllowFreeShops()).thenReturn(false);
        when(config.isAllowPartialTransactions()).thenReturn(true);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getItem((Sign) null)).thenReturn("STONE");
            when(adminBypass.has(any(), any())).thenReturn(true);
            when(inventoryService.fits(any(ItemStack[].class), any())).thenReturn(true);
            // sign null → flagFreeShop returns early; unlimited owner so invalid-shop passes
            PendingTransaction ctx = pending(BUY, true, null, client, owner(),
                    mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
            validator.validate(ctx);
            assertThat(ctx.isCancelled()).isFalse();
        }
    }

    @Test
    void validate_rejectsMissingPrice_forBuy() {
        Player client = player("Notch", UUID.randomUUID());
        when(config.isAllowFreeShops()).thenReturn(true);
        PendingTransaction ctx = pending(BUY, false, sign(location(mock(World.class))), client, owner(),
                mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, io.paradaux.chestshop.utils.PriceUtil.NO_PRICE);
        validator.validate(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.SHOP_DOES_NOT_SELL_THIS_ITEM);
    }

    @Test
    void validate_rejectsMissingPrice_forSell() {
        Player client = player("Notch", UUID.randomUUID());
        when(config.isAllowFreeShops()).thenReturn(true);
        PendingTransaction ctx = pending(SELL, false, sign(location(mock(World.class))), client, owner(),
                mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, io.paradaux.chestshop.utils.PriceUtil.NO_PRICE);
        validator.validate(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.SHOP_DOES_NOT_BUY_THIS_ITEM);
    }

    @Test
    void validate_rejectsEmptyStock() {
        Player client = player("Notch", UUID.randomUUID());
        when(config.isAllowFreeShops()).thenReturn(true);
        PendingTransaction ctx = pending(BUY, false, sign(location(mock(World.class))), client, owner(),
                mock(Inventory.class), new ItemStack[]{null}, new BigDecimal("5"));
        validator.validate(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.INVALID_SHOP);
    }

    @Test
    void validate_rejectsNonAdminShopWithoutContainer() {
        Player client = player("Notch", UUID.randomUUID());
        when(config.isAllowFreeShops()).thenReturn(true);
        Sign s = sign(location(mock(World.class)));
        when(signService.isAdminShop(s)).thenReturn(false);
        PendingTransaction ctx = pending(BUY, false, s, client, owner(),
                null, new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
        validator.validate(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.INVALID_SHOP);
    }

    // ── playernamePattern caching (regex change) ──
    @Test
    void validate_recompilesRegex_whenConfigChanges() {
        Player client = player("Notch", UUID.randomUUID());
        when(config.isAllowFreeShops()).thenReturn(true);
        when(config.isAllowPartialTransactions()).thenReturn(true);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getItem(any(Sign.class))).thenReturn("STONE");
            when(adminBypass.has(any(), any())).thenReturn(true);
            when(inventoryService.fits(any(ItemStack[].class), any())).thenReturn(true);
            PendingTransaction ctx1 = pending(BUY, true, sign(location(mock(World.class))), client, owner(),
                    mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
            validator.validate(ctx1);
            // change the configured regex → pattern must be recompiled on the next validate
            when(config.getValidPlayernameRegexp()).thenReturn("^[A-Za-z]{1,16}$");
            PendingTransaction ctx2 = pending(BUY, true, sign(location(mock(World.class))), client, owner(),
                    mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
            validator.validate(ctx2);
            assertThat(ctx2.isCancelled()).isFalse();
        }
    }

    // ═══════════════════════════ checkFundsAndStock (partial disabled) ═══════════════════════════

    @Test
    void validate_buy_insufficientFunds() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> false;
        PendingTransaction ctx = wholeTradeCtx(BUY, client);
        validator.validate(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.CLIENT_DOES_NOT_HAVE_ENOUGH_MONEY);
    }

    @Test
    void validate_buy_outOfStock() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> true;
        when(inventoryService.hasItems(any(), any())).thenReturn(false);
        PendingTransaction ctx = wholeTradeCtx(BUY, client);
        validator.validate(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_STOCK_IN_CHEST);
    }

    @Test
    void validate_sell_shopOutOfMoney() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> false;
        PendingTransaction ctx = wholeTradeCtx(SELL, client);
        validator.validate(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.SHOP_DOES_NOT_HAVE_ENOUGH_MONEY);
    }

    @Test
    void validate_sell_clientLacksItems() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> true;
        when(inventoryService.hasItems(any(), any())).thenReturn(false);
        PendingTransaction ctx = wholeTradeCtx(SELL, client);
        validator.validate(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_STOCK_IN_INVENTORY);
    }

    @Test
    void validate_buy_passesAllChecks() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> true;
        when(inventoryService.hasItems(any(), any())).thenReturn(true);
        when(inventoryService.fits(any(ItemStack[].class), any())).thenReturn(true);
        when(adminBypass.has(any(), any())).thenReturn(true);
        PendingTransaction ctx = wholeTradeCtx(BUY, client);
        validator.validate(ctx);
        assertThat(ctx.isCancelled()).isFalse();
    }

    private void setupWholeTradeConfig() {
        when(config.isAllowFreeShops()).thenReturn(true);
        when(config.isAllowPartialTransactions()).thenReturn(false);
        lenient().when(adminBypass.has(any(), any())).thenReturn(true);
        lenient().when(inventoryService.fits(any(ItemStack[].class), any())).thenReturn(true);
        lenient().when(accounts.isIgnoring(any(UUID.class))).thenReturn(true); // suppress owner notifications by default
    }

    private PendingTransaction wholeTradeCtx(Transaction.TransactionType type, Player client) {
        Sign s = sign(location(mock(World.class)));
        lenient().when(signService.isAdminShop(s)).thenReturn(false);
        return pending(type, false, s, client, owner(), mock(Inventory.class),
                new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
    }

    // ═══════════════════════════ checkPermissions ═══════════════════════════

    @Test
    void validate_buy_permissionDeniedByHashNode() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> true;
        when(inventoryService.hasItems(any(), any())).thenReturn(true);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<io.paradaux.chestshop.utils.Permissions> perms =
                     mockStatic(io.paradaux.chestshop.utils.Permissions.class)) {
            ss.when(() -> SignService.getItem(any(Sign.class))).thenReturn("#42");
            perms.when(() -> io.paradaux.chestshop.utils.Permissions.hasPermissionSetFalse(eq(client), any()))
                    .thenReturn(true);
            PendingTransaction ctx = wholeTradeCtx(BUY, client);
            validator.validate(ctx);
            assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.CLIENT_DOES_NOT_HAVE_PERMISSION);
        }
    }

    @Test
    void validate_buy_permissionDeniedPerMaterial() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> true;
        when(inventoryService.hasItems(any(), any())).thenReturn(true);
        when(adminBypass.has(any(), any())).thenReturn(false); // no buy permission at all
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getItem(any(Sign.class))).thenReturn("STONE");
            PendingTransaction ctx = wholeTradeCtx(BUY, client);
            validator.validate(ctx);
            assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.CLIENT_DOES_NOT_HAVE_PERMISSION);
        }
    }

    @Test
    void validate_sell_permissionGrantedPerMaterialNode() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> true;
        when(inventoryService.hasItems(any(), any())).thenReturn(true);
        when(inventoryService.fits(any(ItemStack[].class), any())).thenReturn(true);
        // deny the base SELL node but grant the per-material node → second half of the || fires.
        when(adminBypass.has(eq(client), eq(io.paradaux.chestshop.utils.Permissions.SELL))).thenReturn(false);
        when(adminBypass.has(eq(client), eq(io.paradaux.chestshop.utils.Permissions.SELL_ID + "stone"))).thenReturn(true);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getItem(any(Sign.class))).thenReturn("STONE");
            PendingTransaction ctx = wholeTradeCtx(SELL, client);
            validator.validate(ctx);
            assertThat(ctx.isCancelled()).isFalse();
        }
    }

    // ═══════════════════════════ checkStockFits ═══════════════════════════

    @Test
    void validate_sell_noSpaceInChest() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> true;
        when(inventoryService.hasItems(any(), any())).thenReturn(true);
        when(adminBypass.has(any(), any())).thenReturn(true);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getItem(any(Sign.class))).thenReturn("STONE");
            when(inventoryService.fits(any(ItemStack[].class), any())).thenReturn(false); // chest full
            PendingTransaction ctx = wholeTradeCtx(SELL, client);
            when(accounts.isIgnoring(any(UUID.class))).thenReturn(true); // suppress owner notify
            validator.validate(ctx);
            assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_SPACE_IN_CHEST);
        }
    }

    @Test
    void validate_buy_noSpaceInInventory() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> true;
        when(inventoryService.hasItems(any(), any())).thenReturn(true);
        when(adminBypass.has(any(), any())).thenReturn(true);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getItem(any(Sign.class))).thenReturn("STONE");
            when(inventoryService.fits(any(ItemStack[].class), any())).thenReturn(false);
            PendingTransaction ctx = wholeTradeCtx(BUY, client);
            validator.validate(ctx);
            assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.NOT_ENOUGH_SPACE_IN_INVENTORY);
        }
    }

    @Test
    void validate_sell_unlimitedOwner_skipsChestSpaceCheck() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> true;
        when(inventoryService.hasItems(any(), any())).thenReturn(true);
        when(adminBypass.has(any(), any())).thenReturn(true);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getItem(any(Sign.class))).thenReturn("STONE");
            Sign s = sign(location(mock(World.class)));
            when(signService.isAdminShop(s)).thenReturn(true); // unlimited owner ⇒ admin shop
            PendingTransaction ctx = pending(SELL, true, s, client, owner(), null,
                    new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
            validator.validate(ctx);
            assertThat(ctx.isCancelled()).isFalse();
        }
    }

    // ═══════════════════════════ sendErrorMessage (outcome injected via partialFill) ═══════════════════════════

    /** Reach {@code sendErrorMessage} with the given outcome by having partialFill cancel the ctx. */
    private PendingTransaction injectOutcome(TransactionOutcome outcome, Player client, Account owner, Inventory ownerInv) {
        when(config.isAllowFreeShops()).thenReturn(true);
        when(config.isAllowPartialTransactions()).thenReturn(true);
        doAnswer(inv -> { ((PendingTransaction) inv.getArgument(0)).setCancelled(outcome); return null; })
                .when(partialFill).adjustBuy(any());
        Sign s = sign(location(mock(World.class)));
        return pending(BUY, false, s, client, owner, ownerInv, new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
    }

    @Test
    void sendError_clientDepositFailed() {
        Player client = player("Notch", UUID.randomUUID());
        PendingTransaction ctx = injectOutcome(TransactionOutcome.CLIENT_DEPOSIT_FAILED, client, owner(), mock(Inventory.class));
        validator.validate(ctx);
        verify(message).send(client, "chestshop.CLIENT_DEPOSIT_FAILED");
    }

    @Test
    void sendError_shopIsRestricted() {
        Player client = player("Notch", UUID.randomUUID());
        PendingTransaction ctx = injectOutcome(TransactionOutcome.SHOP_IS_RESTRICTED, client, owner(), mock(Inventory.class));
        validator.validate(ctx);
        verify(message).send(client, "chestshop.ACCESS_DENIED");
    }

    @Test
    void sendError_defaultOutcome_sendsNothing() {
        Player client = player("Notch", UUID.randomUUID());
        PendingTransaction ctx = injectOutcome(TransactionOutcome.SPAM_CLICKING_PROTECTION, client, owner(), mock(Inventory.class));
        validator.validate(ctx);
        verify(message, never()).send(any(org.bukkit.command.CommandSender.class), anyString());
    }

    @Test
    void sendError_shopDepositFailed_notifiesOwnerToo() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            UUID ownerId = UUID.randomUUID();
            Account owner = new Account("Alice", "Alice", ownerId);
            Player ownerPlayer = player("Alice", ownerId);
            bk.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(ownerPlayer);
            Player client = player("Notch", UUID.randomUUID());
            PendingTransaction ctx = injectOutcome(TransactionOutcome.SHOP_DEPOSIT_FAILED, client, owner, mock(Inventory.class));
            validator.validate(ctx);
            verify(message).send(client, "chestshop.SHOP_DEPOSIT_FAILED");
            verify(ownerPlayer).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }

    // ═══════════════════════════ sendShopLocationMessage / sendMessageToOwner ═══════════════════════════

    @Test
    void sendError_notEnoughSpaceInChest_notifiesOnlineOwner_worldNamed() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            UUID ownerId = UUID.randomUUID();
            Account owner = new Account("Alice", "Alice", ownerId);
            Player ownerPlayer = player("Alice", ownerId);
            bk.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(ownerPlayer);
            when(accounts.isIgnoring(ownerId)).thenReturn(false); // owner wants the message
            Player client = player("Notch", UUID.randomUUID());
            Sign s = sign(location(world));
            when(config.isAllowFreeShops()).thenReturn(true);
            when(config.isAllowPartialTransactions()).thenReturn(true);
            doAnswer(inv -> { ((PendingTransaction) inv.getArgument(0)).setCancelled(TransactionOutcome.NOT_ENOUGH_SPACE_IN_CHEST); return null; })
                    .when(partialFill).adjustBuy(any());
            PendingTransaction ctx = pending(BUY, false, s, client, owner, mock(Inventory.class),
                    new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));

            validator.validate(ctx);

            verify(ownerPlayer).sendMessage(any(net.kyori.adventure.text.Component.class));
            verify(message).send(client, "chestshop.NOT_ENOUGH_SPACE_IN_CHEST");
        }
    }

    @Test
    void sendError_notEnoughStockInChest_ownerOffline_worldNull() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            UUID ownerId = UUID.randomUUID();
            Account owner = new Account("Alice", "Alice", ownerId);
            bk.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(null); // owner offline → sendMessageToOwner returns early
            when(accounts.isIgnoring(ownerId)).thenReturn(false);
            Player client = player("Notch", UUID.randomUUID());
            Sign s = sign(location(null)); // world unloaded → "?" placeholder
            when(config.isAllowFreeShops()).thenReturn(true);
            when(config.isAllowPartialTransactions()).thenReturn(true);
            doAnswer(inv -> { ((PendingTransaction) inv.getArgument(0)).setCancelled(TransactionOutcome.NOT_ENOUGH_STOCK_IN_CHEST); return null; })
                    .when(partialFill).adjustBuy(any());
            PendingTransaction ctx = pending(BUY, false, s, client, owner, mock(Inventory.class),
                    new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));

            validator.validate(ctx);

            verify(message).send(client, "chestshop.NOT_ENOUGH_STOCK");
        }
    }

    @Test
    void sendError_ownerNotification_respectsCooldown() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            UUID ownerId = UUID.randomUUID();
            Account owner = new Account("Alice", "Alice", ownerId);
            Player ownerPlayer = player("Alice", ownerId);
            bk.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(ownerPlayer);
            when(accounts.isIgnoring(ownerId)).thenReturn(false);
            when(config.getNotificationMessageCooldown()).thenReturn(60L); // 60s window
            Player client = player("Notch", UUID.randomUUID());
            when(config.isAllowFreeShops()).thenReturn(true);
            when(config.isAllowPartialTransactions()).thenReturn(true);
            doAnswer(inv -> { ((PendingTransaction) inv.getArgument(0)).setCancelled(TransactionOutcome.NOT_ENOUGH_SPACE_IN_CHEST); return null; })
                    .when(partialFill).adjustBuy(any());

            PendingTransaction ctx1 = pending(BUY, false, sign(location(world)), client, owner, mock(Inventory.class),
                    new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
            validator.validate(ctx1);
            PendingTransaction ctx2 = pending(BUY, false, sign(location(world)), client, owner, mock(Inventory.class),
                    new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
            validator.validate(ctx2);

            // First notify sent; the second is swallowed by the cooldown.
            verify(ownerPlayer, org.mockito.Mockito.times(1)).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }

    @Test
    void sendError_notEnoughSpaceInChest_suppressedWhenOwnerIgnoresAndConfigOff() {
        Player client = player("Notch", UUID.randomUUID());
        Account owner = owner();
        when(accounts.isIgnoring(owner.getUuid())).thenReturn(true);
        when(config.isShowMessageFullShop()).thenReturn(false);
        PendingTransaction ctx = injectOutcome(TransactionOutcome.NOT_ENOUGH_SPACE_IN_CHEST, client, owner, mock(Inventory.class));
        validator.validate(ctx);
        verify(message).send(client, "chestshop.NOT_ENOUGH_SPACE_IN_CHEST");
    }

    @Test
    void sendError_notEnoughStockInChest_suppressedWhenOwnerIgnoresAndConfigOff() {
        Player client = player("Notch", UUID.randomUUID());
        Account owner = owner();
        when(accounts.isIgnoring(owner.getUuid())).thenReturn(true);
        when(config.isShowMessageOutOfStock()).thenReturn(false);
        PendingTransaction ctx = injectOutcome(TransactionOutcome.NOT_ENOUGH_STOCK_IN_CHEST, client, owner, mock(Inventory.class));
        validator.validate(ctx);
        verify(message).send(client, "chestshop.NOT_ENOUGH_STOCK");
    }

    // ═══════════════════════════ remaining branch fills ═══════════════════════════

    @Test
    void validate_preCancelledCtx_shortCircuitsEveryValidator() {
        Player client = player("Notch", UUID.randomUUID());
        PendingTransaction ctx = pending(BUY, false, sign(location(mock(World.class))), client, owner(),
                mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
        ctx.setCancelled(TransactionOutcome.OTHER); // already cancelled ⇒ each validator returns early
        validator.validate(ctx);
        assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.OTHER);
    }

    @Test
    void validate_freeShopCheck_pricesNotFree_passesThrough() {
        Player client = player("Notch", UUID.randomUUID());
        when(config.isAllowFreeShops()).thenReturn(false);
        when(config.isAllowPartialTransactions()).thenReturn(true);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getPrice(any(Sign.class))).thenReturn("b5:s3"); // not free
            ss.when(() -> SignService.getItem(any(Sign.class))).thenReturn("STONE");
            when(adminBypass.has(any(), any())).thenReturn(true);
            when(inventoryService.fits(any(ItemStack[].class), any())).thenReturn(true);
            PendingTransaction ctx = pending(BUY, true, sign(location(mock(World.class))), client, owner(),
                    mock(Inventory.class), new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
            validator.validate(ctx);
            assertThat(ctx.isCancelled()).isFalse();
        }
    }

    @Test
    void validate_buy_unlimitedOwner_skipsWholeStockCheck() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> true;
        when(adminBypass.has(any(), any())).thenReturn(true);
        when(inventoryService.fits(any(ItemStack[].class), any())).thenReturn(true);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getItem(any(Sign.class))).thenReturn("STONE");
            Sign s = sign(location(mock(World.class)));
            when(signService.isAdminShop(s)).thenReturn(true);
            PendingTransaction ctx = pending(BUY, true, s, client, owner(), null,
                    new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
            validator.validate(ctx);
            assertThat(ctx.isCancelled()).isFalse();
        }
    }

    @Test
    void validate_buy_permissionGrantedPerMaterialNode() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> true;
        when(inventoryService.hasItems(any(), any())).thenReturn(true);
        when(inventoryService.fits(any(ItemStack[].class), any())).thenReturn(true);
        when(adminBypass.has(eq(client), eq(io.paradaux.chestshop.utils.Permissions.BUY))).thenReturn(false);
        when(adminBypass.has(eq(client), eq(io.paradaux.chestshop.utils.Permissions.BUY_ID + "stone"))).thenReturn(true);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getItem(any(Sign.class))).thenReturn("STONE");
            PendingTransaction ctx = wholeTradeCtx(BUY, client);
            validator.validate(ctx);
            assertThat(ctx.isCancelled()).isFalse();
        }
    }

    @Test
    void validate_hashItemLine_notSetFalse_proceedsToLoop() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> true;
        when(inventoryService.hasItems(any(), any())).thenReturn(true);
        when(inventoryService.fits(any(ItemStack[].class), any())).thenReturn(true);
        when(adminBypass.has(any(), any())).thenReturn(true);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getItem(any(Sign.class))).thenReturn("#7"); // '#' present but no set-false override
            PendingTransaction ctx = wholeTradeCtx(BUY, client);
            validator.validate(ctx);
            assertThat(ctx.isCancelled()).isFalse();
        }
    }

    @Test
    void sendError_notEnoughSpaceInChest_notifiesWhenConfigFlagOn() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            UUID ownerId = UUID.randomUUID();
            Account owner = new Account("Alice", "Alice", ownerId);
            Player ownerPlayer = player("Alice", ownerId);
            bk.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(ownerPlayer);
            when(accounts.isIgnoring(ownerId)).thenReturn(true); // ignoring, but the config flag forces the message
            when(config.isShowMessageFullShop()).thenReturn(true);
            when(config.isCstoggleTogglesFullShop()).thenReturn(false);
            Player client = player("Notch", UUID.randomUUID());
            when(config.isAllowFreeShops()).thenReturn(true);
            when(config.isAllowPartialTransactions()).thenReturn(true);
            doAnswer(inv -> { ((PendingTransaction) inv.getArgument(0)).setCancelled(TransactionOutcome.NOT_ENOUGH_SPACE_IN_CHEST); return null; })
                    .when(partialFill).adjustBuy(any());
            PendingTransaction ctx = pending(BUY, false, sign(location(world)), client, owner, mock(Inventory.class),
                    new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
            validator.validate(ctx);
            verify(ownerPlayer).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }

    @Test
    void sendError_notEnoughStockInChest_notifiesWhenConfigFlagOn() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            UUID ownerId = UUID.randomUUID();
            Account owner = new Account("Alice", "Alice", ownerId);
            Player ownerPlayer = player("Alice", ownerId);
            bk.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(ownerPlayer);
            when(accounts.isIgnoring(ownerId)).thenReturn(true);
            when(config.isShowMessageOutOfStock()).thenReturn(true);
            when(config.isCstoggleTogglesOutOfStock()).thenReturn(false);
            Player client = player("Notch", UUID.randomUUID());
            when(config.isAllowFreeShops()).thenReturn(true);
            when(config.isAllowPartialTransactions()).thenReturn(true);
            doAnswer(inv -> { ((PendingTransaction) inv.getArgument(0)).setCancelled(TransactionOutcome.NOT_ENOUGH_STOCK_IN_CHEST); return null; })
                    .when(partialFill).adjustBuy(any());
            PendingTransaction ctx = pending(BUY, false, sign(location(world)), client, owner, mock(Inventory.class),
                    new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
            validator.validate(ctx);
            verify(ownerPlayer).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }

    @Test
    void validate_creativeIgnoredButPlayerSurvival_passes() {
        Player client = player("Notch", UUID.randomUUID());
        when(client.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(config.isIgnoreCreativeMode()).thenReturn(true); // enabled, but the player isn't creative
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> true;
        when(inventoryService.hasItems(any(), any())).thenReturn(true);
        when(inventoryService.fits(any(ItemStack[].class), any())).thenReturn(true);
        when(adminBypass.has(any(), any())).thenReturn(true);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getItem(any(Sign.class))).thenReturn("STONE");
            PendingTransaction ctx = wholeTradeCtx(BUY, client);
            validator.validate(ctx);
            assertThat(ctx.isCancelled()).isFalse();
        }
    }

    @Test
    void validate_sellFreeShop_isFlagged() {
        Player client = player("Notch", UUID.randomUUID());
        when(config.isAllowFreeShops()).thenReturn(false);
        Sign s = sign(location(mock(World.class)));
        when(s.getBlock()).thenReturn(mock(Block.class));
        try (MockedStatic<SignService> ss = mockStatic(SignService.class)) {
            ss.when(() -> SignService.getPrice(s)).thenReturn("b5:s0"); // buy 5 (not free), sell 0 (free)
            PendingTransaction ctx = pending(BUY, false, s, client, owner(), mock(Inventory.class),
                    new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
            validator.validate(ctx);
            assertThat(ctx.isRejectedAsFreeShop()).isTrue();
        }
    }

    @Test
    void validate_buy_permissionDeniedWhenBasePermissionSetFalse() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> true;
        when(inventoryService.hasItems(any(), any())).thenReturn(true);
        when(adminBypass.has(eq(client), eq(io.paradaux.chestshop.utils.Permissions.BUY))).thenReturn(true);
        when(adminBypass.has(eq(client), eq(io.paradaux.chestshop.utils.Permissions.BUY_ID + "stone"))).thenReturn(false);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<io.paradaux.chestshop.utils.Permissions> perms =
                     mockStatic(io.paradaux.chestshop.utils.Permissions.class)) {
            ss.when(() -> SignService.getItem(any(Sign.class))).thenReturn("STONE");
            // base BUY held, but the per-material node is explicitly set-false → the &&-clause fails,
            // and the per-material grant is also absent → denied.
            perms.when(() -> io.paradaux.chestshop.utils.Permissions.hasPermissionSetFalse(
                    eq(client), eq(io.paradaux.chestshop.utils.Permissions.BUY_ID + "stone"))).thenReturn(true);
            PendingTransaction ctx = wholeTradeCtx(BUY, client);
            validator.validate(ctx);
            assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.CLIENT_DOES_NOT_HAVE_PERMISSION);
        }
    }

    @Test
    void validate_sell_permissionDeniedByHashNode() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> true;
        when(inventoryService.hasItems(any(), any())).thenReturn(true);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<io.paradaux.chestshop.utils.Permissions> perms =
                     mockStatic(io.paradaux.chestshop.utils.Permissions.class)) {
            ss.when(() -> SignService.getItem(any(Sign.class))).thenReturn("#9");
            perms.when(() -> io.paradaux.chestshop.utils.Permissions.hasPermissionSetFalse(eq(client), any()))
                    .thenReturn(true); // SELL_ID + "#9" set-false → denied
            PendingTransaction ctx = wholeTradeCtx(SELL, client);
            validator.validate(ctx);
            assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.CLIENT_DOES_NOT_HAVE_PERMISSION);
        }
    }

    @Test
    void validate_sell_permissionDeniedWhenBaseSetFalseAndNoMaterialNode() {
        Player client = player("Notch", UUID.randomUUID());
        setupWholeTradeConfig();
        economy.hasFunds = (u, a) -> true;
        when(inventoryService.hasItems(any(), any())).thenReturn(true);
        when(inventoryService.fits(any(ItemStack[].class), any())).thenReturn(true);
        when(adminBypass.has(eq(client), eq(io.paradaux.chestshop.utils.Permissions.SELL))).thenReturn(true);
        when(adminBypass.has(eq(client), eq(io.paradaux.chestshop.utils.Permissions.SELL_ID + "stone"))).thenReturn(false);
        try (MockedStatic<SignService> ss = mockStatic(SignService.class);
             MockedStatic<io.paradaux.chestshop.utils.Permissions> perms =
                     mockStatic(io.paradaux.chestshop.utils.Permissions.class)) {
            ss.when(() -> SignService.getItem(any(Sign.class))).thenReturn("STONE");
            perms.when(() -> io.paradaux.chestshop.utils.Permissions.hasPermissionSetFalse(
                    eq(client), eq(io.paradaux.chestshop.utils.Permissions.SELL_ID + "stone"))).thenReturn(true);
            PendingTransaction ctx = wholeTradeCtx(SELL, client);
            validator.validate(ctx);
            assertThat(ctx.getTransactionOutcome()).isEqualTo(TransactionOutcome.CLIENT_DOES_NOT_HAVE_PERMISSION);
        }
    }

    @Test
    void sendError_ownerNotification_resendsAfterCooldownExpires() {
        try (MockedStatic<Bukkit> bk = mockStatic(Bukkit.class)) {
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            UUID ownerId = UUID.randomUUID();
            Account owner = new Account("Alice", "Alice", ownerId);
            Player ownerPlayer = player("Alice", ownerId);
            bk.when(() -> Bukkit.getPlayer(ownerId)).thenReturn(ownerPlayer);
            when(accounts.isIgnoring(ownerId)).thenReturn(false);
            when(config.getNotificationMessageCooldown()).thenReturn(1L); // 1-second window
            Player client = player("Notch", UUID.randomUUID());
            when(config.isAllowFreeShops()).thenReturn(true);
            when(config.isAllowPartialTransactions()).thenReturn(true);
            doAnswer(inv -> { ((PendingTransaction) inv.getArgument(0)).setCancelled(TransactionOutcome.NOT_ENOUGH_SPACE_IN_CHEST); return null; })
                    .when(partialFill).adjustBuy(any());

            validator.validate(pending(BUY, false, sign(location(world)), client, owner, mock(Inventory.class),
                    new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5")));
            clockNow[0] += 1100L; // advance past the 1-second cooldown window (deterministic; no sleep)
            validator.validate(pending(BUY, false, sign(location(world)), client, owner, mock(Inventory.class),
                    new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5")));

            // Cooldown expired between the two → both notifications delivered.
            verify(ownerPlayer, org.mockito.Mockito.times(2)).sendMessage(any(net.kyori.adventure.text.Component.class));
        }
    }

    @Test
    void sendError_fullShop_suppressedWhenCstoggleTogglesAndIgnoring() {
        Player client = player("Notch", UUID.randomUUID());
        Account owner = owner();
        when(accounts.isIgnoring(owner.getUuid())).thenReturn(true);
        when(config.isShowMessageFullShop()).thenReturn(true);
        when(config.isCstoggleTogglesFullShop()).thenReturn(true); // cstoggle on → the config term is false
        PendingTransaction ctx = injectOutcome(TransactionOutcome.NOT_ENOUGH_SPACE_IN_CHEST, client, owner, mock(Inventory.class));
        validator.validate(ctx);
        verify(message).send(client, "chestshop.NOT_ENOUGH_SPACE_IN_CHEST");
    }

    @Test
    void sendError_outOfStock_suppressedWhenCstoggleTogglesAndIgnoring() {
        Player client = player("Notch", UUID.randomUUID());
        Account owner = owner();
        when(accounts.isIgnoring(owner.getUuid())).thenReturn(true);
        when(config.isShowMessageOutOfStock()).thenReturn(true);
        when(config.isCstoggleTogglesOutOfStock()).thenReturn(true);
        PendingTransaction ctx = injectOutcome(TransactionOutcome.NOT_ENOUGH_STOCK_IN_CHEST, client, owner, mock(Inventory.class));
        validator.validate(ctx);
        verify(message).send(client, "chestshop.NOT_ENOUGH_STOCK");
    }

    @Test
    void removeFlaggedFreeShop_guardsAgainstNullSign() {
        // Defensive guard: a free-shop rejection flag with no sign is a no-op (the destructive
        // removal needs a block to break). rejectedAsFreeShop is set via its public setter here;
        // an invalid client name cancels early so removeFlaggedFreeShop is the only step that runs.
        Player client = player("bad name!", UUID.randomUUID());
        when(signService.isAdminShop("bad name!")).thenReturn(false);
        PendingTransaction ctx = pending(BUY, false, null, client, owner(), mock(Inventory.class),
                new ItemStack[]{item(Material.STONE, 1)}, new BigDecimal("5"));
        ctx.setRejectedAsFreeShop(true);

        validator.validate(ctx);

        verify(signBreak, never()).sendShopDestroyed(any(), any());
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
