package io.paradaux.chestshop.services.impl;
import io.paradaux.chestshop.utils.Colours;
import io.paradaux.chestshop.utils.SignText;
import lombok.extern.slf4j.Slf4j;

import io.paradaux.chestshop.utils.BlockUtil;
import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.ProtectionService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.services.AdminBypassService;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.ShopService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.utils.Permissions;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.model.ShopCreation;
import io.paradaux.chestshop.model.CreatedShop;
import io.paradaux.chestshop.model.DestroyedShop;
import io.paradaux.chestshop.model.ShopCreation.CreationOutcome;
import io.paradaux.chestshop.services.StockCounterService;
import io.paradaux.chestshop.services.MarketSyncService;
import io.paradaux.chestshop.utils.LocationUtil;
import io.paradaux.chestshop.utils.MaterialUtil;
import io.paradaux.chestshop.utils.PriceUtil;
import io.paradaux.chestshop.utils.StringUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.Locale;

import static io.paradaux.chestshop.utils.Permissions.NOFEE;
import static io.paradaux.chestshop.services.SignService.AUTOFILL_CODE;

/**
 * Owns the whole shop lifecycle: creation validation ({@link #create}), the post-creation
 * and post-removal reactions ({@link #onCreated}/{@link #onDestroyed}), and the
 * creation-fee / removal-refund money (with the mirrored server-economy movement).
 * This replaces ChestShop's old event-bus design — a {@code ShopCreation} fanned
 * out to a dozen priority-ordered validator classes coordinating through a mutable
 * outcome/signLines bag — with one service whose validation steps are ordinary ordered
 * private methods (PAR-282). The genuine cross-cutting hooks (market-DB sync, stock
 * counter) and {@code ProtectionService} integration stay.
 *
 * <p>Shops have no ChestShop-owned persistence — they are sign + chest world state — so
 * there is no repository here, only this service.
 */
@Singleton
@Slf4j
public class ShopServiceImpl implements ShopService {

    private final AccountService accounts;
    private final EconomyService economy;
    private final ItemService items;
    private final ProtectionService protection;
    private final StockCounterService stockCounter;
    private final Message message;
    private final MarketSyncService market;
    private final ChestShopConfiguration config;
    private final SignService signService;
    private final ShopBlockService shopBlockService;

    private final AdminBypassService adminBypass;

    @Inject
    public ShopServiceImpl(AccountService accounts, EconomyService economy, ItemService items, ProtectionService protection, StockCounterService stockCounter, Message message, MarketSyncService market,
                       ChestShopConfiguration config, SignService signService, ShopBlockService shopBlockService, AdminBypassService adminBypass) {
        this.adminBypass = adminBypass;
        this.accounts = accounts;
        this.economy = economy;
        this.items = items;
        this.protection = protection;
        this.stockCounter = stockCounter;
        this.message = message;
        this.market = market;
        this.config = config;
        this.signService = signService;
        this.shopBlockService = shopBlockService;
    }

    @Override
    public ShopCreation create(Player player, Sign sign, String[] signLines) {
        ShopCreation ctx = new ShopCreation(player, sign, signLines);

        // LOWEST
        checkItem(ctx);
        checkPrice(ctx);
        checkQuantity(ctx);
        // LOW
        checkChest(ctx);
        resolveName(ctx);
        // NORMAL
        checkCreationFunds(ctx);
        rejectFreeShop(ctx);
        checkTerrain(ctx);
        // HIGH
        if (config.isBlockShopsWithSellPriceHigherThanBuyPrice()) {
            checkPriceRatio(ctx);
        }
        checkCreationPermission(ctx);
        if (!ctx.isCancelled()) { // CreationFeeGetter ran @HIGH with ignoreCancelled=true
            chargeCreationFeeStep(ctx);
        }
        // StockCounterListener ran @HIGH with the default ignoreCancelled=false, so it
        // fires regardless: it normalises the quantity line and seeds the stock counter.
        stockCounter.onPreShopCreation(ctx);
        // HIGHEST — the name second pass (ignoreCancelled=true)
        if (!ctx.isCancelled()) {
            resolveName(ctx);
        }
        // MONITOR — tell the player why it failed
        sendCreationError(ctx);

        return ctx;
    }

    // ---- creation validation steps (were the preshopcreation listeners) ---------

    /** Resolve/normalise the item line; autofill from the chest, or reject an invalid item. */
    private void checkItem(ShopCreation ctx) {
        String itemCode = SignService.getItem(ctx.getSignLines());
        ItemStack item = items.parse(itemCode);

        if (item == null) {
            if (config.isAllowAutoItemFill() && itemCode.equals(AUTOFILL_CODE)) {
                Container container = shopBlockService.findConnectedContainer(ctx.getSign());
                if (container != null) {
                    for (ItemStack stack : container.getInventory().getContents()) {
                        if (!MaterialUtil.isEmpty(stack)) {
                            item = stack;
                            break;
                        }
                    }
                }
                if (item == null) {
                    ctx.setSignLine(SignService.ITEM_LINE, Colours.BOLD + SignService.AUTOFILL_CODE);
                    ctx.setOutcome(CreationOutcome.ITEM_AUTOFILL);
                    return;
                }
            } else {
                ctx.setOutcome(CreationOutcome.INVALID_ITEM);
                return;
            }
        }

        itemCode = items.getSignName(item);
        if (StringUtil.getMinecraftStringWidth(itemCode) > MaterialUtil.MAXIMUM_SIGN_WIDTH) {
            ctx.setOutcome(CreationOutcome.INVALID_ITEM);
            return;
        }
        ctx.setSignLine(SignService.ITEM_LINE, itemCode);
    }

    /** Normalise the price line (B/S prefixes, precision, multipliers) and reject if invalid.
     *  Package-private so the price-parser unit test can exercise it directly. */
    void checkPrice(ShopCreation ctx) {
        String line = SignService.getPrice(ctx.getSignLines()).toUpperCase(Locale.ROOT);
        if (config.getPricePrecision() <= 0) {
            line = line.replaceAll("\\.\\d*", ""); // remove too many decimal places
        } else {
            line = line.replaceAll("(\\.\\d{0," + config.getPricePrecision() + "})\\d*", "$1");
        }
        line = line.replaceAll("(\\.\\d*[1-9])0+", "$1"); // trailing zeroes
        line = line.replaceAll("(\\d)\\.0+(\\D|$)", "$1$2"); // point + zeroes when only trailing zeros

        String[] parts = line.split(":");
        if (parts.length > 1 && (isInvalidPricePart(parts[0]) ^ isInvalidPricePart(parts[1]))) {
            line = line.replace(':', ' ');
            parts = new String[]{line};
        }
        if (parts[0].split(" ").length > 2
                || line.indexOf('B') != line.lastIndexOf('B')
                || line.indexOf('S') != line.lastIndexOf('S')) {
            ctx.setOutcome(CreationOutcome.INVALID_PRICE);
            return;
        }
        if (PriceUtil.isPrice(parts[0])) {
            line = "B " + line;
        }
        if (parts.length > 1 && PriceUtil.isPrice(parts[1])) {
            line += " S";
        }
        if (line.length() > 15) {
            line = line.replace(" ", "");
        }
        if (line.length() > 15) {
            ctx.setOutcome(CreationOutcome.INVALID_PRICE);
            return;
        }
        for (String part : parts) {
            if (!PriceUtil.hasSingleMultiplier(part)) {
                ctx.setOutcome(CreationOutcome.INVALID_PRICE);
                return;
            }
        }
        ctx.setSignLine(SignService.PRICE_LINE, line);
        if (!PriceUtil.hasBuyPrice(line) && !PriceUtil.hasSellPrice(line)) {
            ctx.setOutcome(CreationOutcome.INVALID_PRICE);
        }
    }

    private static boolean isInvalidPricePart(String part) {
        for (char character : new char[]{'B', 'S'}) {
            if (part.contains(Character.toString(character))) {
                return !PriceUtil.hasPrice(part, character);
            }
        }
        return false;
    }

    /** Reject a missing / out-of-range quantity line. */
    private void checkQuantity(ShopCreation ctx) {
        int amount = -1;
        try {
            amount = SignService.getQuantity(ctx.getSignLines());
        } catch (NumberFormatException ignored) {} // not a quantity on the line
        if (amount < 1 || amount > config.getMaxShopAmount()) {
            ctx.setOutcome(CreationOutcome.INVALID_QUANTITY);
        }
    }

    /** Require a backing chest (non-admin) the creator may access. */
    private void checkChest(ShopCreation ctx) {
        String nameLine = SignService.getOwner(ctx.getSignLines());
        Container connectedContainer = shopBlockService.findConnectedContainer(ctx.getSign().getBlock());

        if (connectedContainer == null) {
            if (!signService.isAdminShop(nameLine)) {
                ctx.setOutcome(CreationOutcome.NO_CHEST);
            }
            return;
        }
        Player player = ctx.getPlayer();
        if (adminBypass.has(player, Permissions.ADMIN)) {
            return;
        }
        if (!protection.canAccess(player, connectedContainer.getBlock())) {
            ctx.setOutcome(CreationOutcome.NO_PERMISSION_FOR_CHEST);
        }
    }

    /** Charge / require the configured creation fee. */
    private void checkCreationFunds(ShopCreation ctx) {
        BigDecimal shopCreationPrice = config.getShopCreationPrice();
        if (shopCreationPrice.compareTo(BigDecimal.ZERO) == 0
                || signService.isAdminShop(ctx.getSignLines())
                || adminBypass.has(ctx.getPlayer(), NOFEE)) {
            return;
        }
        if (!economy.hasFunds(ctx.getPlayer().getUniqueId(), shopCreationPrice)) {
            ctx.setOutcome(CreationOutcome.NOT_ENOUGH_MONEY);
        }
    }

    /** DemocracyCraft: reject free (price-0) shops unless {@code ALLOW_FREE_SHOPS} (PAR-88).
     *  Package-private so the free-shop unit test can exercise it directly. */
    void rejectFreeShop(ShopCreation ctx) {
        if (config.isAllowFreeShops()) {
            return;
        }
        String price = SignService.getPrice(ctx.getSignLines());
        if (isFreePrice(PriceUtil.getExactBuyPrice(price)) || isFreePrice(PriceUtil.getExactSellPrice(price))) {
            ctx.setOutcome(CreationOutcome.INVALID_PRICE);
        }
    }

    private static boolean isFreePrice(BigDecimal price) {
        return price.compareTo(PriceUtil.FREE) == 0;
    }

    /** Require terrain build permission for the sign and its chest. */
    private void checkTerrain(ShopCreation ctx) {
        Player player = ctx.getPlayer();
        if (!protection.canPlaceSign(player, ctx.getSign())) {
            ctx.setOutcome(CreationOutcome.NO_PERMISSION_FOR_TERRAIN);
            return;
        }
        Container connectedContainer = shopBlockService.findConnectedContainer(ctx.getSign().getBlock());
        Location containerLocation = connectedContainer != null ? connectedContainer.getLocation() : null;
        if (!protection.canBuild(player, containerLocation, ctx.getSign().getLocation())) {
            ctx.setOutcome(CreationOutcome.NO_PERMISSION_FOR_TERRAIN);
        }
    }

    /** Optionally reject a shop whose sell price exceeds its buy price. */
    private void checkPriceRatio(ShopCreation ctx) {
        String priceLine = SignService.getPrice(ctx.getSignLines());
        if (PriceUtil.hasBuyPrice(priceLine) && PriceUtil.hasSellPrice(priceLine)
                && PriceUtil.getExactSellPrice(priceLine).compareTo(PriceUtil.getExactBuyPrice(priceLine)) > 0) {
            ctx.setOutcome(CreationOutcome.SELL_PRICE_HIGHER_THAN_BUY_PRICE);
        }
    }

    /** Per-name / per-item shop-creation permission check. */
    private void checkCreationPermission(ShopCreation ctx) {
        Player player = ctx.getPlayer();

        if (ctx.getOwnerAccount() != null
                && !accounts.canUseName(player, Permissions.OTHER_NAME_CREATE, ctx.getOwnerAccount().getShortName())) {
            ctx.setSignLine(SignService.NAME_LINE, "");
            ctx.setOutcome(CreationOutcome.NO_PERMISSION);
            return;
        }

        String priceLine = SignService.getPrice(ctx.getSignLines());
        String itemLine = SignService.getItem(ctx.getSignLines());
        ItemStack item = items.parse(itemLine);

        if (item == null) {
            if (PriceUtil.hasBuyPrice(priceLine) && !adminBypass.has(player, Permissions.SHOP_CREATION_BUY)
                    || PriceUtil.hasSellPrice(priceLine) && !adminBypass.has(player, Permissions.SHOP_CREATION_SELL)) {
                ctx.setOutcome(CreationOutcome.NO_PERMISSION);
            }
            return;
        }

        String matID = item.getType().toString().toLowerCase(Locale.ROOT);
        String[] parts = itemLine.split("#", 2);
        if (parts.length == 2 && Permissions.hasPermissionSetFalse(player, Permissions.SHOP_CREATION_ID + matID + "#" + parts[1])) {
            ctx.setOutcome(CreationOutcome.NO_PERMISSION);
            return;
        }

        if (PriceUtil.hasBuyPrice(priceLine)) {
            if (!canCreateForItem(player, Permissions.SHOP_CREATION_BUY_ID + matID, matID, Permissions.SHOP_CREATION_BUY)) {
                ctx.setOutcome(CreationOutcome.NO_PERMISSION);
            }
        } else if (PriceUtil.hasSellPrice(priceLine)) {
            if (!canCreateForItem(player, Permissions.SHOP_CREATION_SELL_ID + matID, matID, Permissions.SHOP_CREATION_SELL)) {
                ctx.setOutcome(CreationOutcome.NO_PERMISSION);
            }
        }
    }

    /** Shared buy/sell creation-permission test for a specific material. */
    private boolean canCreateForItem(Player player, String perItemSideNode, String matID, String sideNode) {
        return adminBypass.has(player, perItemSideNode)
                || adminBypass.has(player, Permissions.SHOP_CREATION)
                || adminBypass.has(player, Permissions.SHOP_CREATION_ID + matID) && adminBypass.has(player, sideNode);
    }

    /** Charge the creation fee (the former {@code ignoreCancelled=true} CreationFeeGetter step). */
    private void chargeCreationFeeStep(ShopCreation ctx) {
        if (!chargeCreationFee(ctx.getPlayer(), ctx.getSignLines())) {
            ctx.setOutcome(CreationOutcome.NOT_ENOUGH_MONEY);
            ctx.setSignLines(new String[4]);
        }
    }

    /** Resolve the owner account from the name line (player or B:&lt;id&gt; business). */
    private void resolveName(ShopCreation ctx) {
        String name = SignService.getOwner(ctx.getSignLines());
        Player player = ctx.getPlayer();

        if (SignService.isBusinessAccount(name)) {
            resolveBusinessName(ctx, player, name);
            return;
        }

        Account account = ctx.getOwnerAccount();
        if (account == null || !account.getShortName().equalsIgnoreCase(name)) {
            account = null;
            try {
                if (name.isEmpty() || !accounts.canUseName(player, Permissions.OTHER_NAME_CREATE, name)) {
                    account = accounts.getOrCreateAccount(player);
                } else {
                    account = accounts.resolveAccount(name);
                    if (account == null) {
                        Player otherPlayer = ChestShop.getBukkitServer().getPlayer(name);
                        try {
                            account = otherPlayer != null
                                    ? accounts.getOrCreateAccount(otherPlayer)
                                    : accounts.getOrCreateAccount(ChestShop.getBukkitServer().getOfflinePlayer(name));
                        } catch (IllegalArgumentException e) {
                            ctx.getPlayer().sendMessage(e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error while trying to check account for name " + name + " with player " + player.getName(), e);
            }
        }
        ctx.setOwnerAccount(account);
        if (account != null) {
            ctx.setSignLine(SignService.NAME_LINE, account.getShortName());
        } else {
            ctx.setSignLine(SignService.NAME_LINE, "");
            ctx.setOutcome(CreationOutcome.UNKNOWN_PLAYER);
        }
    }

    /**
     * Validate and resolve a business-account ({@code B:<base36>}) name: Treasury must be
     * present, the account must exist, and (unless the creator is a ChestShop admin) the
     * creator must hold the firm's CHESTSHOP permission.
     */
    private void resolveBusinessName(ShopCreation ctx, Player player, String name) {
        Account existing = ctx.getOwnerAccount();
        if (existing != null && existing.getShortName().equalsIgnoreCase(name)) {
            return; // already resolved on a previous pass
        }

        if (Bukkit.getPluginManager().getPlugin("Treasury") == null) {
            message.send(player, "chestshop.TREASURY_REQUIRED");
            failName(ctx);
            return;
        }
        Account account = accounts.resolveAccount(name);
        if (account == null) {
            message.send(player, "chestshop.BUSINESS_ACCOUNT_NOT_FOUND");
            failName(ctx);
            return;
        }
        if (!adminBypass.has(player, Permissions.ADMIN) && !accounts.canAccess(player, account)) {
            message.send(player, "chestshop.BUSINESS_NO_CHESTSHOP_PERMISSION");
            failName(ctx);
            return;
        }
        ctx.setOwnerAccount(account);
        int accountId = SignService.getBusinessAccountId(account.getShortName());
        ctx.setSignLine(SignService.NAME_LINE, SignService.businessAccountSignName(accountId));
    }

    private void failName(ShopCreation ctx) {
        ctx.setSignLine(SignService.NAME_LINE, "");
        ctx.setOutcome(CreationOutcome.UNKNOWN_PLAYER);
    }

    /** Tell the creator why the shop could not be made (was the MONITOR ErrorMessageSender). */
    private void sendCreationError(ShopCreation ctx) {
        if (!ctx.isCancelled()) {
            return;
        }
        String messageKey = switch (ctx.getOutcome()) {
            case UNKNOWN_PLAYER -> "chestshop.PLAYER_NOT_FOUND";
            case INVALID_ITEM -> "chestshop.INCORRECT_ITEM_ID";
            case INVALID_PRICE -> "chestshop.INVALID_SHOP_PRICE";
            case INVALID_QUANTITY -> "chestshop.INVALID_SHOP_QUANTITY";
            case SELL_PRICE_HIGHER_THAN_BUY_PRICE -> "chestshop.SELL_PRICE_HIGHER_THAN_BUY_PRICE";
            case NO_CHEST -> "chestshop.NO_CHEST_DETECTED";
            case NO_PERMISSION -> "chestshop.NO_PERMISSION";
            case NO_PERMISSION_FOR_TERRAIN -> "chestshop.CANNOT_CREATE_SHOP_HERE";
            case NO_PERMISSION_FOR_CHEST -> "chestshop.CANNOT_ACCESS_THE_CHEST";
            case NOT_ENOUGH_MONEY -> "chestshop.NOT_ENOUGH_MONEY";
            case ITEM_AUTOFILL -> "chestshop.CLICK_TO_AUTOFILL_ITEM";
            default -> null;
        };
        if (messageKey != null) {
            message.send(ctx.getPlayer(), messageKey);
        }
    }

    @Override
    public void onCreated(CreatedShop event) {
        stickSignToChest(event);   // was @NORMAL SignSticker
        sendCreatedMessage(event); // was @MONITOR MessageSender
        logCreation(event);        // was @MONITOR ShopCreationLogger
        market.onShopCreated(event); // genuine market-DB sync — stays
    }

    @Override
    public void onDestroyed(DestroyedShop event) {
        refundOnRemoval(event.getDestroyer(), event.getSign());
        logRemoval(event);                     // was @MONITOR ShopRemovalLogger
        market.onShopDestroyed(event); // genuine market-DB sync — stays
    }

    // ---- post-creation reactions (were the postshopcreation listeners) ----------

    private static final String CREATION_LOG = "%1$s created %2$s - %3$s - %4$s - at %5$s";
    private static final String REMOVAL_LOG = "%1$s was removed by %2$s - %3$s - %4$s - at %5$s";

    /** Stick a freshly-created shop sign onto its chest (config-gated, never for admin shops). */
    private void stickSignToChest(CreatedShop event) {
        if (!config.isStickSignsToChests() || signService.isAdminShop(event.getSignLines())) {
            return;
        }

        Block signBlock = event.getSign().getBlock();
        if (!(signBlock.getBlockData() instanceof org.bukkit.block.data.type.Sign)) {
            return;
        }

        BlockFace shopBlockFace = null;
        for (BlockFace face : BlockUtil.CHEST_EXTENSION_FACES) {
            if (shopBlockService.couldBeShopContainer(signBlock.getRelative(face))) {
                shopBlockFace = face;
                break;
            }
        }
        if (shopBlockFace == null) {
            return;
        }

        int index = signBlock.getType().name().indexOf("SIGN");
        if (index < 0) {
            return;
        }
        Material newMaterial = Material.valueOf(signBlock.getType().name().substring(0, index) + "WALL_SIGN");
        signBlock.setType(newMaterial);

        Sign sign = (Sign) signBlock.getState();
        WallSign signMaterial = (WallSign) Bukkit.createBlockData(newMaterial);
        signMaterial.setFacing(shopBlockFace.getOppositeFace());
        sign.setBlockData(signMaterial);

        String[] lines = event.getSignLines();
        for (int i = 0; i < lines.length; ++i) {
            SignText.setLine(sign, i, lines[i]);
        }
        sign.update(true);
    }

    /** Notify the creator that their shop was made. */
    private void sendCreatedMessage(CreatedShop event) {
        message.send(event.getPlayer(), "chestshop.SHOP_CREATED");
    }

    /** Write a shop-creation line to the shop log (off the main thread). */
    private void logCreation(CreatedShop event) {
        ChestShop.runInAsyncThread(() -> {
            String creator = event.getPlayer().getName();
            String shopOwner = SignService.getOwner(event.getSignLines());
            String typeOfShop = signService.isAdminShop(shopOwner)
                    ? "an Admin Shop"
                    : "a shop" + (event.createdByOwner() ? "" : " for " + event.getOwnerAccount().getName());
            String item = SignService.getQuantity(event.getSignLines()) + ' ' + SignService.getItem(event.getSignLines());
            String prices = SignService.getPrice(event.getSignLines());
            String location = LocationUtil.locationToString(event.getSign().getLocation());
            log.info(String.format(CREATION_LOG, creator, typeOfShop, item, prices, location));
        });
    }

    /** Write a shop-removal line to the shop log (off the main thread). */
    private void logRemoval(DestroyedShop event) {
        if (!config.isLogAllShopRemovals() && event.getDestroyer() != null) {
            return;
        }
        ChestShop.runInAsyncThread(() -> {
            String shopOwner = SignService.getOwner(event.getSign());
            String typeOfShop = signService.isAdminShop(shopOwner)
                    ? "An Admin Shop"
                    : "A shop belonging to " + shopOwner;
            String item = SignService.getQuantity(event.getSign()) + ' ' + SignService.getItem(event.getSign());
            String prices = SignService.getPrice(event.getSign());
            String location = LocationUtil.locationToString(event.getSign().getLocation());
            log.info(String.format(REMOVAL_LOG,
                    typeOfShop,
                    event.getDestroyer() != null ? event.getDestroyer().getName() : "???",
                    item, prices, location));
        });
    }

    @Override
    public boolean chargeCreationFee(Player player, String[] signLines) {
        BigDecimal price = config.getShopCreationPrice();

        if (price.compareTo(BigDecimal.ZERO) == 0
                || signService.isAdminShop(signLines)
                || adminBypass.has(player, NOFEE)) {
            return true;
        }

        if (!economy.withdraw(player.getUniqueId(), price, player.getWorld())) {
            return false;
        }

        // Mirror the collected fee into the server-economy pool. If that leg fails the player
        // has already been charged, so compensate (refund) rather than pocket the money into the
        // void and falsely report SHOP_FEE_PAID (chestshop/behaviour/0002).
        if (!creditServerEconomy(price, player.getWorld())) {
            log.warn("ChestShop: creation fee charged to " + player.getName()
                    + " but the server-economy credit failed; refunding the player.");
            economy.deposit(player.getUniqueId(), price, player.getWorld());
            return false;
        }

        message.send(player, "chestshop.SHOP_FEE_PAID", "amount", economy.format(price));
        return true;
    }

    @Override
    public void refundOnRemoval(Player destroyer, Sign sign) {
        BigDecimal refund = config.getShopRefundPrice();

        if (destroyer == null || adminBypass.has(destroyer, NOFEE) || refund.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        if (StringUtil.stripColourCodes(SignService.getItem(sign)).equals(AUTOFILL_CODE)) {
            return;
        }

        Account account = accounts.resolveAccount(SignService.getOwner(sign));
        if (account == null) {
            return;
        }

        // Debit the mirror leg (the server-economy account) FIRST and gate on it. Crediting the
        // owner before confirming the funding leg can succeed risks a net mint if the debit fails
        // (chestshop/behaviour/0001). Only credit the owner once the mirror leg is covered, and
        // only announce the refund once the owner credit itself is confirmed.
        if (!debitServerEconomy(refund, sign.getWorld())) {
            return;
        }

        if (!economy.deposit(account.getUuid(), refund, sign.getWorld())) {
            return;
        }

        message.send(destroyer, "chestshop.SHOP_REFUNDED", "amount", economy.format(refund));
    }

    /**
     * Mirror a collected creation fee into the server-economy account, if one is configured.
     *
     * @return whether the credit was confirmed. No server-economy account configured → the fee
     *         is a deliberate sink (SYSTEM), which counts as success.
     */
    private boolean creditServerEconomy(BigDecimal amount, World world) {
        Account serverAccount = accounts.getServerEconomyAccount();
        if (serverAccount == null) {
            return true;
        }
        return economy.deposit(serverAccount.getUuid(), amount, world);
    }

    /**
     * Mirror an issued refund out of the server-economy account, if one is configured.
     *
     * @return whether the debit succeeded. No server-economy account configured → SYSTEM is the
     *         deliberate faucet, which counts as success.
     */
    private boolean debitServerEconomy(BigDecimal amount, World world) {
        Account serverAccount = accounts.getServerEconomyAccount();
        if (serverAccount == null) {
            return true;
        }
        return economy.withdraw(serverAccount.getUuid(), amount, world);
    }
}
