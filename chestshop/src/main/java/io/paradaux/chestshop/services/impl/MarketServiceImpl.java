package io.paradaux.chestshop.services.impl;
import io.paradaux.chestshop.utils.SignText;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.business.api.BusinessApi;
import io.paradaux.business.model.Firm;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.ItemCodeService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.services.MarketService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.utils.MaterialUtil;
import io.paradaux.chestshop.utils.PriceUtil;
import io.paradaux.treasury.api.MarketApi;
import io.paradaux.treasury.api.ShopQueryApi;
import io.paradaux.treasury.api.TreasuryApi;
import io.paradaux.treasury.api.market.ChestShopSaleRecord;
import io.paradaux.treasury.api.market.ChestShopShopRecord;
import io.paradaux.treasury.model.economy.Account;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Sign;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Holds the Treasury market APIs (resolved at enable) and builds the MarketApi DTOs from ChestShop
 * data. Merged the former static {@code MarketHook} (API holder) + {@code MarketRecords} (DTO
 * builder) into one service (PAR-316). Custom-item identity routes through {@link ItemService} so
 * a Nexo/custom item resolves to its real code, falling back to vanilla when unclaimed.
 */
@Singleton
@Slf4j
public class MarketServiceImpl implements MarketService {

    /** Mirrors ChestShop's synthetic-UUID scheme for business accounts: UUID(MSB, accountId). */
    static final long BUSINESS_UUID_MSB = 0xC5B0000000000000L;

    private final ItemService items;
    private final ItemCodeService itemCodes;
    private final InventoryService inventoryService;

    private volatile MarketApi market;
    private volatile ShopQueryApi shopQuery;
    private volatile TreasuryApi treasury;
    private volatile BusinessApi business;

    @Inject
    public MarketServiceImpl(ItemService items, ItemCodeService itemCodes, InventoryService inventoryService) {
        this.items = items;
        this.itemCodes = itemCodes;
        this.inventoryService = inventoryService;
    }

    @Override
    public void init() {
        market = load(MarketApi.class);
        shopQuery = load(ShopQueryApi.class);
        treasury = load(TreasuryApi.class);
        business = load(BusinessApi.class);
        log.info("ChestShop market tracker {}",
                enabled() ? "enabled" : "disabled (Treasury MarketApi not available)");
        log.info("ChestShop /find search {}",
                searchEnabled() ? "enabled" : "disabled (Treasury ShopQueryApi not available)");
    }

    private static <T> T load(Class<T> type) {
        try {
            RegisteredServiceProvider<T> rsp = Bukkit.getServicesManager().getRegistration(type);
            return rsp != null ? rsp.getProvider() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public boolean enabled() {
        return market != null && treasury != null;
    }

    @Override
    public boolean searchEnabled() {
        return shopQuery != null;
    }

    @Override
    public MarketApi market() {
        return market;
    }

    @Override
    public ShopQueryApi shopQuery() {
        return shopQuery;
    }

    @Override
    public TreasuryApi treasury() {
        return treasury;
    }

    @Override
    public BusinessApi business() {
        return business;
    }

    // ── owner classification ───────────────────────────────────────────────

    @Override
    public Owner ownerFromUuid(UUID ownerUuid, boolean adminShop) {
        if (adminShop || ownerUuid == null) {
            return new Owner(null, null, null, null, true);
        }
        int accountId;
        if (ownerUuid.getMostSignificantBits() == BUSINESS_UUID_MSB) {
            accountId = (int) ownerUuid.getLeastSignificantBits();
        } else {
            Account personal = treasury.getAccountByUUID(ownerUuid);
            if (personal == null) {
                return new Owner(null, null, null, null, false);
            }
            accountId = personal.getAccountId();
        }
        return classify(accountId);
    }

    private Owner classify(int accountId) {
        Account acc = treasury.getAccountById(accountId);
        if (acc == null) {
            return new Owner(accountId, null, null, null, false);
        }
        String type = acc.getAccountType() != null ? acc.getAccountType().name() : null;
        UUID ownerUuid = "PERSONAL".equals(type) ? acc.getOwnerUuid() : null;
        Integer firmId = null;
        if ("BUSINESS".equals(type) && business != null) {
            Firm firm = business.firms().getFirmByAccountId(accountId);
            if (firm != null) {
                firmId = firm.getFirmId();
            }
        }
        return new Owner(accountId, type, firmId, ownerUuid, false);
    }

    // ── item identity (custom-aware, via ItemService) ──────────────────────

    private String canonicalCode(ItemStack item) {
        try {
            return items.getName(item, 0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String itemKey(ItemStack item) {
        String code = canonicalCode(item);
        return code != null ? code : itemCodes.encode(item, MaterialUtil.MAXIMUM_SIGN_WIDTH);
    }

    private String itemName(ItemStack item) {
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            net.kyori.adventure.text.Component displayName = meta != null ? meta.displayName() : null;
            if (displayName != null) {
                String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(displayName).trim();
                if (!name.isEmpty()) {
                    return name;
                }
            }
        }
        if (isCustom(item)) {
            String code = canonicalCode(item);
            if (code != null && !code.isBlank()) {
                return pretty(code);
            }
        }
        return pretty(item.getType().name());
    }

    /** "DIAMOND_SWORD" / "nexo:ruby_sword#02" -> "Diamond Sword" / "Ruby Sword". */
    private static String pretty(String raw) {
        if (raw == null) {
            return null;
        }
        String base = raw;
        int hash = base.indexOf('#');
        if (hash >= 0) {
            base = base.substring(0, hash);
        }
        int colon = base.lastIndexOf(':');
        if (colon >= 0) {
            base = base.substring(colon + 1);
        }
        StringBuilder sb = new StringBuilder();
        for (String word : base.toLowerCase(Locale.ROOT).split("[_\\s]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.length() > 0 ? sb.toString() : raw;
    }

    private boolean isCustom(ItemStack item) {
        String vanilla = itemCodes.encode(item, MaterialUtil.MAXIMUM_SIGN_WIDTH);
        String canonical = canonicalCode(item);
        if (canonical != null && vanilla != null && !canonical.equalsIgnoreCase(vanilla)) {
            return true;
        }
        return (vanilla != null && vanilla.contains("#")) || item.hasItemMeta();
    }

    private String itemData(ItemStack item) {
        if (!isCustom(item)) {
            return null;
        }
        try {
            YamlConfiguration yc = new YamlConfiguration();
            yc.set("item", item);
            return yc.saveToString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ── stock + DTO builders ───────────────────────────────────────────────

    @Override
    public int stockOf(ItemStack item, Inventory inventory) {
        return inventory == null ? 0 : inventoryService.getAmount(item, inventory);
    }

    @Override
    public Integer capacityOf(ItemStack item, Inventory inventory) {
        return inventory == null ? null : inventoryService.getRemainingCapacity(item, inventory);
    }

    private static BigDecimal nonNegativeOrNull(BigDecimal v) {
        return (v == null || v.signum() < 0) ? null : v;
    }

    @Override
    public ChestShopSaleRecord sale(Sign sign, ItemStack item, int quantity, UUID customer,
                                    Owner owner, BigDecimal total, BigDecimal tax,
                                    String direction, Integer shopStock, Long txnId) {
        Location l = sign.getLocation();
        BigDecimal unit = quantity > 0
                ? total.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP)
                : total;
        return new ChestShopSaleRecord(
                txnId, direction, customer,
                owner.accountId(), owner.type(), owner.firmId(), owner.ownerUuid(), owner.admin(),
                item.getType().name(), itemKey(item), itemName(item), isCustom(item), itemData(item),
                quantity, unit, total, tax != null ? tax : BigDecimal.ZERO,
                worldName(l), l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                owner.admin() ? null : shopStock);
    }

    @Override
    public ChestShopShopRecord shop(Sign sign, ItemStack item, Owner owner,
                                    Integer currentStock, Integer estimatedCapacity) {
        Location l = sign.getLocation();
        String priceLine = SignText.getLine(sign, SignService.PRICE_LINE);
        int batch;
        try {
            batch = SignService.getQuantity(sign);
        } catch (RuntimeException e) {
            batch = Math.max(1, item.getAmount());
        }
        return new ChestShopShopRecord(
                worldName(l), l.getBlockX(), l.getBlockY(), l.getBlockZ(), owner.admin(),
                owner.accountId(), owner.type(), owner.firmId(), owner.ownerUuid(),
                item.getType().name(), itemKey(item), itemName(item), isCustom(item), itemData(item),
                nonNegativeOrNull(PriceUtil.getExactBuyPrice(priceLine)),
                nonNegativeOrNull(PriceUtil.getExactSellPrice(priceLine)),
                batch, owner.admin() ? null : currentStock,
                owner.admin() ? null : estimatedCapacity, worldUuid(l));
    }

    @Override
    public int totalAmount(ItemStack[] stock) {
        return Arrays.stream(stock).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum();
    }

    private static String worldName(Location l) {
        return l.getWorld() != null ? l.getWorld().getName() : null;
    }

    private static UUID worldUuid(Location l) {
        return l.getWorld() != null ? l.getWorld().getUID() : null;
    }
}
