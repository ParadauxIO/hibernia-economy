package io.paradaux.chestshop.model.config;
import lombok.extern.slf4j.Slf4j;

import io.paradaux.chestshop.ChestShop;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationComponent;
import io.paradaux.hibernia.framework.configurator.annotations.ConfigurationValue;
import lombok.Getter;
import org.bukkit.Material;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * ChestShop's configuration, loaded by HiberniaFramework's Configurator from
 * {@code config.yml}. The flat UPPER_SNAKE_CASE keys match the historical
 * (breeze) layout exactly, so existing operator configs keep working.
 *
 * <p>The framework binds the supported scalar/enum/{@code BigDecimal} types
 * directly; the few types it can't bind natively ({@link UUID}, {@code Set}s of
 * {@link Material}/{@code String}) are stored as {@code String}/{@code List} and
 * exposed through the hand-written adapter getters below. This component is the
 * single source of truth: it is injected wherever config is read (the former
 * static {@code Properties} mirror was removed in PAR-282), and the Configurator
 * repopulates this same instance in place on reload so injected references stay valid.
 *
 * @author Acrobot (original config), migrated to HiberniaFramework
 */
@ConfigurationComponent
@Getter
@Slf4j
public class ChestShopConfiguration {

    @ConfigurationValue(path = "DEBUG", defaultValue = "false")
    private boolean debug;

    @ConfigurationValue(path = "CACHE_SIZE", defaultValue = "1000")
    private int cacheSize;

    @ConfigurationValue(path = "STRIP_PRICE_COLORS", defaultValue = "false")
    private boolean stripPriceColors;

    /** Stored as a string list; exposed as an {@code EnumSet<Material>} via {@link #getShopContainers()}. */
    @ConfigurationValue(path = "SHOP_CONTAINERS", defaultValue = "")
    private List<String> shopContainersRaw;

    @ConfigurationValue(path = "SHOP_INTERACTION_INTERVAL", defaultValue = "250")
    private int shopInteractionInterval;

    @ConfigurationValue(path = "IGNORE_CREATIVE_MODE", defaultValue = "true")
    private boolean ignoreCreativeMode;

    @ConfigurationValue(path = "IGNORE_ACCESS_PERMS", defaultValue = "true")
    private boolean ignoreAccessPerms;

    @ConfigurationValue(path = "REVERSE_BUTTONS", defaultValue = "false")
    private boolean reverseButtons;

    @ConfigurationValue(path = "SHIFT_SELLS_IN_STACKS", defaultValue = "false")
    private boolean shiftSellsInStacks;

    @ConfigurationValue(path = "SHIFT_SELLS_EVERYTHING", defaultValue = "false")
    private boolean shiftSellsEverything;

    @ConfigurationValue(path = "SHIFT_ALLOWS", defaultValue = "ALL")
    private String shiftAllows;

    @ConfigurationValue(path = "ALLOW_SIGN_CHEST_OPEN", defaultValue = "false")
    private boolean allowSignChestOpen;

    @ConfigurationValue(path = "SIGN_DYING", defaultValue = "true")
    private boolean signDying;

    @ConfigurationValue(path = "ALLOW_LEFT_CLICK_DESTROYING", defaultValue = "true")
    private boolean allowLeftClickDestroying;

    @ConfigurationValue(path = "REMOVE_EMPTY_SHOPS", defaultValue = "false")
    private boolean removeEmptyShops;

    @ConfigurationValue(path = "REMOVE_EMPTY_CHESTS", defaultValue = "false")
    private boolean removeEmptyChests;

    /** Stored as a string list; exposed as a {@code Set<String>} via {@link #getRemoveEmptyWorlds()}. */
    @ConfigurationValue(path = "REMOVE_EMPTY_WORLDS", defaultValue = "")
    private List<String> removeEmptyWorldsRaw;

    @ConfigurationValue(path = "ADMIN_SHOP_NAME", defaultValue = "Admin Shop")
    private String adminShopName;

    @ConfigurationValue(path = "FORCE_UNLIMITED_ADMIN_SHOP", defaultValue = "false")
    private boolean forceUnlimitedAdminShop;

    @ConfigurationValue(path = "SERVER_ECONOMY_ACCOUNT", defaultValue = "")
    private String serverEconomyAccount;

    /** Stored as a string; exposed as a {@link UUID} via {@link #getServerEconomyAccountUuid()}. */
    @ConfigurationValue(path = "SERVER_ECONOMY_ACCOUNT_UUID", defaultValue = "00000000-0000-0000-0000-000000000000")
    private String serverEconomyAccountUuidRaw;

    @ConfigurationValue(path = "TAX_AMOUNT", defaultValue = "0")
    private double taxAmount;

    @ConfigurationValue(path = "SERVER_TAX_AMOUNT", defaultValue = "0")
    private double serverTaxAmount;

    @ConfigurationValue(path = "SHOP_CREATION_PRICE", defaultValue = "0")
    private BigDecimal shopCreationPrice;

    @ConfigurationValue(path = "SHOP_REFUND_PRICE", defaultValue = "0")
    private BigDecimal shopRefundPrice;

    @ConfigurationValue(path = "PRICE_PRECISION", defaultValue = "2")
    private int pricePrecision;

    @ConfigurationValue(path = "ENSURE_CORRECT_PLAYERID", defaultValue = "false")
    private boolean ensureCorrectPlayerid;

    @ConfigurationValue(path = "VALID_PLAYERNAME_REGEXP", defaultValue = "^\\.?\\w+$")
    private String validPlayernameRegexp;

    @ConfigurationValue(path = "BLOCK_SHOPS_WITH_SELL_PRICE_HIGHER_THAN_BUY_PRICE", defaultValue = "true")
    private boolean blockShopsWithSellPriceHigherThanBuyPrice;

    @ConfigurationValue(path = "ALLOW_FREE_SHOPS", defaultValue = "false")
    private boolean allowFreeShops;

    @ConfigurationValue(path = "MAX_SHOP_AMOUNT", defaultValue = "3456")
    private int maxShopAmount;

    @ConfigurationValue(path = "ALLOW_MULTIPLE_SHOPS_AT_ONE_BLOCK", defaultValue = "false")
    private boolean allowMultipleShopsAtOneBlock;

    @ConfigurationValue(path = "ALLOW_PARTIAL_TRANSACTIONS", defaultValue = "true")
    private boolean allowPartialTransactions;

    @ConfigurationValue(path = "ALLOW_AUTO_ITEM_FILL", defaultValue = "true")
    private boolean allowAutoItemFill;

    @ConfigurationValue(path = "SHOW_MESSAGE_OUT_OF_STOCK", defaultValue = "true")
    private boolean showMessageOutOfStock;

    @ConfigurationValue(path = "SHOW_MESSAGE_FULL_SHOP", defaultValue = "true")
    private boolean showMessageFullShop;

    @ConfigurationValue(path = "NOTIFICATION_MESSAGE_COOLDOWN", defaultValue = "10")
    private long notificationMessageCooldown;

    @ConfigurationValue(path = "CSTOGGLE_TOGGLES_OUT_OF_STOCK", defaultValue = "false")
    private boolean cstoggleTogglesOutOfStock;

    @ConfigurationValue(path = "CSTOGGLE_TOGGLES_FULL_SHOP", defaultValue = "false")
    private boolean cstoggleTogglesFullShop;

    @ConfigurationValue(path = "SHOW_TRANSACTION_INFORMATION_CLIENT", defaultValue = "true")
    private boolean showTransactionInformationClient;

    @ConfigurationValue(path = "SHOW_TRANSACTION_INFORMATION_OWNER", defaultValue = "true")
    private boolean showTransactionInformationOwner;

    @ConfigurationValue(path = "LOG_ALL_SHOP_REMOVALS", defaultValue = "true")
    private boolean logAllShopRemovals;

    @ConfigurationValue(path = "STACK_TO_64", defaultValue = "false")
    private boolean stackTo64;

    @ConfigurationValue(path = "USE_BUILT_IN_PROTECTION", defaultValue = "true")
    private boolean useBuiltInProtection;

    @ConfigurationValue(path = "STICK_SIGNS_TO_CHESTS", defaultValue = "false")
    private boolean stickSignsToChests;

    @ConfigurationValue(path = "TURN_OFF_DEFAULT_PROTECTION_WHEN_PROTECTED_EXTERNALLY", defaultValue = "false")
    private boolean turnOffDefaultProtectionWhenProtectedExternally;

    @ConfigurationValue(path = "TURN_OFF_SIGN_PROTECTION", defaultValue = "false")
    private boolean turnOffSignProtection;

    @ConfigurationValue(path = "TURN_OFF_HOPPER_PROTECTION", defaultValue = "false")
    private boolean turnOffHopperProtection;

    @ConfigurationValue(path = "CHECK_ACCESS_FOR_SHOP_USE", defaultValue = "false")
    private boolean checkAccessForShopUse;

    @ConfigurationValue(path = "WORLDGUARD_INTEGRATION", defaultValue = "false")
    private boolean worldguardIntegration;

    @ConfigurationValue(path = "WORLDGUARD_USE_FLAG", defaultValue = "false")
    private boolean worldguardUseFlag;

    @ConfigurationValue(path = "WORLDGUARD_USE_PROTECTION", defaultValue = "false")
    private boolean worldguardUseProtection;

    @ConfigurationValue(path = "GRIEFPREVENTION_INTEGRATION", defaultValue = "false")
    private boolean griefpreventionIntegration;

    @ConfigurationValue(path = "USE_STOCK_COUNTER", defaultValue = "false")
    private boolean useStockCounter;

    /** Stored as a string list; exposed as a {@code Set<String>} via {@link #getExcludedItemAttributes()}. */
    @ConfigurationValue(path = "EXCLUDED_ITEM_ATTRIBUTES", defaultValue = "")
    private List<String> excludedItemAttributesRaw;

    // ── Adapter getters for the types the Configurator can't bind natively ──────

    /** Allowed shop container materials, parsed from the configured names. */
    public Set<Material> getShopContainers() {
        EnumSet<Material> set = EnumSet.noneOf(Material.class);
        if (shopContainersRaw != null) {
            for (String name : shopContainersRaw) {
                Material m = Material.getMaterial(name.toUpperCase(Locale.ROOT));
                if (m != null) {
                    set.add(m);
                } else {
                    log.warn(name + " is not a valid Material name in the config!");
                }
            }
        }
        return set;
    }

    /** Worlds in which empty shops are removed (empty = all worlds). */
    public Set<String> getRemoveEmptyWorlds() {
        return removeEmptyWorldsRaw == null ? new LinkedHashSet<>() : new LinkedHashSet<>(removeEmptyWorldsRaw);
    }

    /** UUID of the server economy account (defaults to the zero UUID). */
    public UUID getServerEconomyAccountUuid() {
        return UUID.fromString(serverEconomyAccountUuidRaw);
    }

    /** Item-meta attributes excluded from the item-similarity comparison. */
    public Set<String> getExcludedItemAttributes() {
        return excludedItemAttributesRaw == null ? new LinkedHashSet<>() : new LinkedHashSet<>(excludedItemAttributesRaw);
    }
}
