package io.paradaux.treasuryrestapi.service;

import io.paradaux.treasuryrestapi.dto.ChestShopItemDetailResponse;
import io.paradaux.treasuryrestapi.dto.ChestShopItemResponse;
import io.paradaux.treasuryrestapi.dto.ChestShopItemsResponse;
import io.paradaux.treasuryrestapi.dto.ChestShopMarketStatsResponse;
import io.paradaux.treasuryrestapi.dto.ChestShopPricePoint;
import io.paradaux.treasuryrestapi.dto.ChestShopShopResponse;
import io.paradaux.treasuryrestapi.dto.ChestShopShopsResponse;
import io.paradaux.treasuryrestapi.exception.ApiException;
import io.paradaux.treasuryrestapi.mapper.ChestShopMapper;
import io.paradaux.treasuryrestapi.model.ChestShopItemRow;
import io.paradaux.treasuryrestapi.model.ChestShopItemStatsRow;
import io.paradaux.treasuryrestapi.model.ChestShopMarketStatsRow;
import io.paradaux.treasuryrestapi.model.ChestShopPriceDayRow;
import io.paradaux.treasuryrestapi.model.ChestShopShopRow;
import io.paradaux.treasuryrestapi.util.Pagination;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Read-only market service over the ChestShop analytics tables. Everything here
 * is public, aggregate, non-financial-per-entity data (the sensitive
 * per-customer/per-seller drilldowns that economy-explorer login-gates are
 * deliberately not exposed).
 *
 * <p>Built for heavy read traffic: the aggregate endpoints ({@link #listItems},
 * {@link #getItem}, {@link #marketStats}) are {@link Cacheable} with a short TTL
 * so a stampede of identical requests collapses to one query per window; the
 * shop directory ({@link #listShops}) is uncached (its filter space is too wide
 * to cache usefully) but is a single index-served table with a hard page cap.
 */
@Service
public class ChestShopService {

    /** {@code chestshop_*.item_key} VARCHAR(190). */
    private static final int MAX_ITEM_KEY_LENGTH = 190;
    /** {@code chestshop_*.material} VARCHAR(64). */
    private static final int MAX_MATERIAL_LENGTH = 64;
    private static final int MAX_LIMIT = 100;
    /** Reject pathological 1-char {@code LIKE '%x%'} scans up front. */
    private static final int MIN_SEARCH_LENGTH = 2;
    private static final int MAX_SEARCH_LENGTH = 100;
    private static final int MAX_WINDOW_DAYS = 365;
    /** Cheapest live shops surfaced on the item-detail view. */
    private static final int ITEM_DETAIL_SHOP_LIMIT = 10;

    private final ChestShopMapper mapper;

    public ChestShopService(ChestShopMapper mapper) {
        this.mapper = mapper;
    }

    // ── shops ────────────────────────────────────────────────────────────────────

    public ChestShopShopsResponse listShops(String itemKey, String material, Integer firmId,
                                            boolean buyable, boolean inStock, String search,
                                            int page, int limit) {
        int safeLimit = validateLimit(limit);
        int offset = validateOffset(page, safeLimit);
        String safeItemKey = validateOptional(itemKey, MAX_ITEM_KEY_LENGTH, "itemKey");
        String safeMaterial = validateOptional(material, MAX_MATERIAL_LENGTH, "material");
        String safeSearch = validateSearch(search);

        List<ChestShopShopRow> rows = mapper.listShops(safeItemKey, safeMaterial, firmId,
                buyable ? 1 : 0, inStock ? 1 : 0, safeSearch, safeLimit, offset);
        long totalItems = mapper.countShops(safeItemKey, safeMaterial, firmId,
                buyable ? 1 : 0, inStock ? 1 : 0, safeSearch);

        List<ChestShopShopResponse> items = rows.stream().map(ChestShopService::toShopResponse).toList();
        return new ChestShopShopsResponse(page, totalPages(totalItems, safeLimit), totalItems, items);
    }

    // ── items ────────────────────────────────────────────────────────────────────

    @Cacheable(cacheNames = "chestshopItems",
            key = "(#search == null ? '' : #search.trim().toLowerCase()) + ':' + #page + ':' + #limit")
    public ChestShopItemsResponse listItems(String search, int page, int limit) {
        int safeLimit = validateLimit(limit);
        int offset = validateOffset(page, safeLimit);
        String safeSearch = validateSearch(search);

        List<ChestShopItemRow> rows = mapper.listItems(safeSearch, safeLimit, offset);
        long totalItems = mapper.countItems(safeSearch);

        List<ChestShopItemResponse> items = rows.stream().map(ChestShopService::toItemResponse).toList();
        return new ChestShopItemsResponse(page, totalPages(totalItems, safeLimit), totalItems, items);
    }

    // ADT-50: normalise the cache key the same way the method normalises its query
    // input (validateRequired trims), so 'diamond' / ' diamond' / 'diamond ' share
    // one cache entry instead of three that all resolve to the same trimmed query.
    @Cacheable(cacheNames = "chestshopItemDetail", key = "(#itemKey == null ? '' : #itemKey.trim()) + ':' + #days")
    public ChestShopItemDetailResponse getItem(String itemKey, int days) {
        String safeItemKey = validateRequired(itemKey, MAX_ITEM_KEY_LENGTH, "itemKey");
        int safeDays = validateDays(days);
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(safeDays);

        ChestShopItemStatsRow stats = mapper.itemStats(safeItemKey, since);
        long activeShops = mapper.countActiveShopsForItem(safeItemKey);

        // Found iff the item has ever traded OR a live shop trades it now.
        if ((stats == null || stats.getAllTimeTrades() == 0) && activeShops == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND",
                    "No ChestShop activity for item '" + safeItemKey + "'.");
        }

        List<ChestShopPricePoint> priceByDay = mapper.itemPriceByDay(safeItemKey, since).stream()
                .map(ChestShopService::toPricePoint).toList();
        // Reuse the shop directory: cheapest in-stock, buyable shops for this item.
        List<ChestShopShopResponse> cheapest = mapper.listShops(safeItemKey, null, null,
                        1, 1, null, ITEM_DETAIL_SHOP_LIMIT, 0).stream()
                .map(ChestShopService::toShopResponse).toList();

        BigDecimal volume = stats.getTotalVolume() != null ? stats.getTotalVolume() : BigDecimal.ZERO;
        String avgUnit = avgUnitPrice(volume, stats.getTotalQuantity());

        return new ChestShopItemDetailResponse(
                stats.getItemKey(),
                stats.getMaterial(),
                stats.getItemName(),
                stats.isItemCustom(),
                safeDays,
                stats.getTradeCount(),
                stats.getTotalQuantity(),
                volume.toPlainString(),
                avgUnit,
                activeShops,
                cheapest,
                priceByDay);
    }

    // ── stats ────────────────────────────────────────────────────────────────────

    @Cacheable(cacheNames = "chestshopStats")
    public ChestShopMarketStatsResponse marketStats() {
        ChestShopMarketStatsRow row = mapper.marketStats();
        long activeShops = mapper.countActiveShops();
        BigDecimal volume = row.getTotalVolume() != null ? row.getTotalVolume() : BigDecimal.ZERO;
        return new ChestShopMarketStatsResponse(
                row.getTotalSales(), volume.toPlainString(), row.getDistinctItems(), activeShops);
    }

    // ── validation ────────────────────────────────────────────────────────────────

    private static int validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM",
                    "Query parameter 'limit' must be between 1 and " + MAX_LIMIT + ".");
        }
        return limit;
    }

    private static int validateOffset(int page, int limit) {
        if (page < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM",
                    "Query parameter 'page' must be >= 1.");
        }
        return Pagination.offset(page, limit);
    }

    private static int validateDays(int days) {
        if (days < 1 || days > MAX_WINDOW_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM",
                    "Query parameter 'days' must be between 1 and " + MAX_WINDOW_DAYS + ".");
        }
        return days;
    }

    /** Trim; treat blank as absent (null); enforce min/max length when present. */
    private static String validateSearch(String search) {
        if (search == null) return null;
        String trimmed = search.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() < MIN_SEARCH_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM",
                    "Query parameter 'search' must be at least " + MIN_SEARCH_LENGTH + " characters.");
        }
        if (trimmed.length() > MAX_SEARCH_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM",
                    "Query parameter 'search' must be at most " + MAX_SEARCH_LENGTH + " characters.");
        }
        return trimmed;
    }

    /** Optional exact-match filter: blank → null; over-long → 400 (can't match anyway). */
    private static String validateOptional(String value, int maxLength, String name) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > maxLength) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM",
                    "Query parameter '" + name + "' must be at most " + maxLength + " characters.");
        }
        return trimmed;
    }

    private static String validateRequired(String value, int maxLength, String name) {
        String trimmed = validateOptional(value, maxLength, name);
        if (trimmed == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM",
                    "Path variable '" + name + "' is required.");
        }
        return trimmed;
    }

    // ── mapping ───────────────────────────────────────────────────────────────────

    private static int totalPages(long totalItems, int limit) {
        return (int) Math.ceil((double) totalItems / limit);
    }

    private static String avgUnitPrice(BigDecimal totalVolume, long totalQuantity) {
        if (totalQuantity <= 0) return "0";
        return totalVolume.divide(BigDecimal.valueOf(totalQuantity), 4, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String money(BigDecimal value) {
        return value != null ? value.toPlainString() : null;
    }

    private static Instant utc(LocalDateTime value) {
        return value != null ? value.toInstant(ZoneOffset.UTC) : null;
    }

    private static ChestShopShopResponse toShopResponse(ChestShopShopRow r) {
        return new ChestShopShopResponse(
                r.getShopId(), r.getWorld(), r.getSignX(), r.getSignY(), r.getSignZ(),
                r.isAdminShop(), r.getShopAccountType(), r.getShopFirmId(),
                r.getShopOwnerUuid() != null ? r.getShopOwnerUuid().toString() : null,
                r.getOwnerName(), r.getMaterial(), r.getItemKey(), r.getItemName(), r.isItemCustom(),
                money(r.getBuyPrice()), money(r.getSellPrice()), r.getBatchQty(), r.getCurrentStock(),
                utc(r.getStockAt()), utc(r.getLastSeen()));
    }

    private static ChestShopItemResponse toItemResponse(ChestShopItemRow r) {
        return new ChestShopItemResponse(
                r.getItemKey(), r.getMaterial(), r.getItemName(), r.isItemCustom(),
                r.getTradeCount(), r.getTotalQuantity(),
                (r.getTotalVolume() != null ? r.getTotalVolume() : BigDecimal.ZERO).toPlainString());
    }

    private static ChestShopPricePoint toPricePoint(ChestShopPriceDayRow r) {
        return new ChestShopPricePoint(
                r.getDay(), r.getSales(), r.getTotalQuantity(),
                (r.getTotalVolume() != null ? r.getTotalVolume() : BigDecimal.ZERO).toPlainString(),
                (r.getAvgUnitPrice() != null ? r.getAvgUnitPrice() : BigDecimal.ZERO).toPlainString());
    }
}
