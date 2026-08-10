package io.paradaux.chestshop.services;
import io.paradaux.chestshop.utils.SignText;

import io.paradaux.chestshop.utils.InventoryUtil;
import io.paradaux.chestshop.utils.BlockUtil;
import io.paradaux.chestshop.utils.QuantityUtil;
import io.paradaux.chestshop.utils.StringUtil;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Sign;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.paradaux.chestshop.utils.BlockUtil.getState;
import static io.paradaux.chestshop.utils.MaterialUtil.DURABILITY;
import static io.paradaux.chestshop.utils.MaterialUtil.METADATA;

/**
 * @author Acrobot
 */
@Singleton
public class SignService {

    private final ChestShopConfiguration config;

    @Inject
    public SignService(ChestShopConfiguration config) {
        this.config = config;
    }

    public static final byte NAME_LINE = 0;
    public static final byte QUANTITY_LINE = 1;
    public static final byte PRICE_LINE = 2;
    public static final byte ITEM_LINE = 3;

    public static final Pattern[][] SHOP_SIGN_PATTERN = {
            { Pattern.compile("^[1-9][0-9]{0,5}$"), QuantityUtil.QUANTITY_LINE_WITH_COUNTER_PATTERN },
            {
                Pattern.compile("(?i)^((\\d*([.e]\\d+)?)([KM])?|free)$"),
                Pattern.compile("(?i)^([BS] *((\\d+([.e]\\d+)?([KM])?)|free))( *: *([BS] *((\\d+([.e]\\d+)?([KM])?)|free)))?$"),
                Pattern.compile("(?i)^(((\\d+([.e]\\d+)?([KM] )?)|free) *[BS])( *: *([BS] *((\\d+([.e]\\d+)?([KM])?)|free)))?$"),
                Pattern.compile("(?i)^(((\\d+([.e]\\d+)?([KM] )?)|free) *[BS]) *: *(((\\d+([.e]\\d+)?([KM] )?)|free) *[BS])$"),
                Pattern.compile("(?i)^([BS] *((\\d+([.e]\\d+)?([KM])?)|free)) *: *(((\\d+([.e]\\d+)?([KM] )?)|free) *[BS])$"),
            },
            {
                Pattern.compile("^\\?$"),
                Pattern.compile("^[\\p{L}\\d_ \\-]+(" + DURABILITY.pattern() + ")?(" + METADATA.pattern() + ")?$"),
                Pattern.compile("^[\\p{L}\\d_ \\-]+" + METADATA.pattern() + "(" + DURABILITY.pattern() + ")?$")
            }
    };
    public static final String AUTOFILL_CODE = "?";

    private static final Pattern BUSINESS_PATTERN = Pattern.compile("(?i)^B:[0-9A-Z]+$");

    public static boolean isBusinessAccount(String owner) {
        return BUSINESS_PATTERN.matcher(owner).matches();
    }

    public static boolean isBusinessAccount(String[] lines) {
        return isBusinessAccount(getOwner(lines));
    }

    public static int getBusinessAccountId(String owner) {
        return Integer.parseInt(owner.substring(2), 36);
    }

    public static String businessAccountSignName(int accountId) {
        return "B:" + Integer.toString(accountId, 36).toUpperCase(Locale.ROOT);
    }

    public boolean isAdminShop(String owner) {
        return owner.replace(" ", "").equalsIgnoreCase(config.getAdminShopName().replace(" ", ""));
    }

    public boolean isAdminShop(Sign sign) {
        return isAdminShop(sign.getLines());
    }

    public boolean isAdminShop(String[] lines) {
        return isAdminShop(getOwner(lines));
    }

    public boolean isValid(Sign sign) {
        return isValid(sign.getLines());
    }

    public boolean isValid(String[] lines) {
        lines = StringUtil.stripColourCodes(lines);
        return validateSign(lines)
                && (getPrice(lines).toUpperCase(Locale.ROOT).contains("B")
                        || getPrice(lines).toUpperCase(Locale.ROOT).contains("S"))
                && !getOwner(lines).isEmpty();
    }

    // Constant "name:id" disambiguation suffix (duplicate/too-long names) — compiled once.
    private static final Pattern NAME_WITH_ID = Pattern.compile("^(.+):[A-Za-z0-9]+$");
    // The valid-playername regex comes from config and can change on reload, so cache it
    // by its source string rather than recompiling on every sign validation.
    private volatile String cachedPlayernameRegexp;
    private volatile Pattern cachedPlayernamePattern;

    /**
     * Whether the given sign lines form a structurally valid ChestShop sign (owner name,
     * the three configured shop-sign line patterns, single-colon price line). Pure
     * sign-format validation — was misplaced on {@code ItemService} behind the static
     * {@code ChestShop.items()} locator (PAR-282).
     */
    public boolean validateSign(String[] lines) {
        String ownerName = getOwner(lines);

        // Validate the owner as a player name unless it is blank (auto-filled), an admin
        // shop, or a business token (B:<base36 id> / legacy b:<FirmName>) resolved elsewhere.
        if (!isAdminShop(ownerName) && !ownerName.isEmpty()
                && !ownerName.regionMatches(true, 0, "B:", 0, 2)) {
            Matcher withId = NAME_WITH_ID.matcher(ownerName);
            if (withId.matches()) {
                // The name carries a disambiguation id — validate the part before the last ':'.
                ownerName = withId.group(1);
            }
            if (!validNamePattern().matcher(ownerName).matches()) {
                return false;
            }
        }

        // The last three lines must each match one of the configured shop-sign patterns.
        for (int i = 0; i < 3; i++) {
            boolean matches = false;
            for (Pattern pattern : SHOP_SIGN_PATTERN[i]) {
                if (pattern.matcher(StringUtil.strip(StringUtil.stripColourCodes(lines[i + 1]))).matches()) {
                    matches = true;
                    break;
                }
            }
            if (!matches) {
                return false;
            }
        }

        // A valid prepared sign has at most one ':' in the price line.
        String priceLine = getPrice(lines);
        return priceLine.indexOf(':') == priceLine.lastIndexOf(':');
    }

    private Pattern validNamePattern() {
        String regexp = config.getValidPlayernameRegexp();
        if (!regexp.equals(cachedPlayernameRegexp)) {
            cachedPlayernamePattern = Pattern.compile(regexp);
            cachedPlayernameRegexp = regexp;
        }
        return cachedPlayernamePattern;
    }

    public boolean isValid(Block sign) {
        return BlockUtil.isSign(sign) && isValid((Sign) getState(sign, false));
    }

    // isShopChest/isShopBlock (block-level shop detection) moved to ShopBlockService
    // (PAR-282) — they are block geometry, not sign-line logic, and hosting them there
    // lets ShopBlockService depend on this class directly (isValid) with no back-edge,
    // dissolving the former ShopBlockUtil↔SignService construction cycle.

    public Block getShopBlock(InventoryHolder holder) {
        if (holder instanceof DoubleChest) {
            return Optional.ofNullable(getShopBlock(InventoryUtil.getLeftSide((DoubleChest) holder, false)))
                    .orElse(getShopBlock(InventoryUtil.getRightSide((DoubleChest) holder, false)));
        } else if (holder instanceof BlockState) {
            return ((BlockState) holder).getBlock();
        }
        return null;
    }

    // canAccess / hasPermission / isOwner (the account-access predicates) moved to
    // AccountService (PAR-282) — they were ledger logic living on a sign util.


    /**
     * Get the owner string of a shop sign
     * @param sign The sign
     * @return The owner string
     */
    public static String getOwner(Sign sign) {
        return getOwner(sign.getLines());
    }

    /**
     * Get the owner string of a shop sign
     * @param lines The sign lines
     * @return The owner string
     */
    public static String getOwner(String[] lines) {
        return StringUtil.stripColourCodes(StringUtil.strip(StringUtil.stripColourCodes(lines[NAME_LINE])));
    }

    /**
     * Get the quantity and count line of the shop sign
     * @param sign The sign
     * @return The quantity line
     * @throws IllegalArgumentException Thrown when an invalid quantity is present
     */
    public static String getQuantityLine(Sign sign) throws IllegalArgumentException {
        return getQuantityLine(sign.getLines());
    }

    /**
     * Get the quantity and count line of sign lines
     * @param lines The sign lines
     * @return The quantity line
     * @throws IllegalArgumentException Thrown when an invalid quantity is present
     */
    public static String getQuantityLine(String[] lines) throws IllegalArgumentException {
        return lines.length > QUANTITY_LINE ? StringUtil.strip(StringUtil.stripColourCodes(lines[QUANTITY_LINE])) : "";
    }

    /**
     * Get the quantity of the shop sign
     * @param sign The sign
     * @return The quantity line
     * @throws IllegalArgumentException Thrown when an invalid quantity is present
     */
    public static int getQuantity(Sign sign) throws IllegalArgumentException {
        return getQuantity(sign.getLines());
    }

    /**
     * Get the quantity of sign lines
     * @param lines The sign lines
     * @return The quantity
     * @throws IllegalArgumentException Thrown when an invalid quantity is present
     */
    public static int getQuantity(String[] lines) throws IllegalArgumentException {
        return QuantityUtil.parseQuantity(getQuantityLine(lines));
    }

    /**
     * Get the price line of the shop sign
     * @param sign The sign
     * @return The price line
     */
    public static String getPrice(Sign sign) {
        return StringUtil.strip(StringUtil.stripColourCodes(SignText.getLine(sign, PRICE_LINE)));
    }

    /**
     * Get the price line of sign lines
     * @param lines The sign lines
     * @return The price line
     */
    public static String getPrice(String[] lines) {
        return lines.length > PRICE_LINE ? StringUtil.strip(StringUtil.stripColourCodes(lines[PRICE_LINE])) : "";
    }

    /**
     * Get the item line of the shop sign
     * @param sign The sign
     * @return The item line
     */
    public static String getItem(Sign sign) {
        return getItem(sign.getLines());
    }

    /**
     * Get the item line of sign lines
     * @param lines The sign lines
     * @return The item line
     */
    public static String getItem(String[] lines) {
        return lines.length > ITEM_LINE ? StringUtil.strip(StringUtil.stripColourCodes(lines[ITEM_LINE])) : "";
    }
}
