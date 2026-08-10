package io.paradaux.chestshop.dialogs;

import io.paradaux.chestshop.model.FoundShop;
import io.paradaux.chestshop.model.SortDirection;
import io.paradaux.chestshop.model.ShopType;
import io.paradaux.chestshop.model.ShopAttribute;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The {@code /find} query model — the Usher {@code @Model} shared across the find
 * dialog's screens. Holds what to search for (item key, fuzzy), where the player
 * is (for distance), the shop-type / hide-empty-full filters, the multi-key sort,
 * and (after a query) the result page. Ported from the legacy chestshop-database
 * {@code FindState}: the registry does the thin item/world filtering in SQL, this
 * does the shop-type filtering, hide-empty/full, distance and weighted sort in
 * memory — the only place that knows the querying player's position.
 */
public final class FindState {

    /** Results shown per page in the dialog list. */
    public static final int PAGE_SIZE = 7;

    /** Direction + weight for one selected sort attribute. */
    public static final class SortMeta {
        private SortDirection direction;
        private int weight;

        SortMeta(SortDirection direction, int weight) {
            this.direction = direction;
            this.weight = weight;
        }

        public SortDirection direction() { return direction; }
        public int weight() { return weight; }
        void direction(SortDirection d) { this.direction = d; }
        void weight(int w) { this.weight = w; }
    }

    private final ItemStack itemStack;          // for the dialog item body; may be null
    private final String itemKey;               // the search key (canonical sign code)
    private final String itemDisplayName;
    private final String queryWorld;            // player's world name, or null
    private final int qx, qy, qz;
    private final boolean hasPosition;

    private boolean fuzzy = false;
    private final EnumSet<ShopType> shopTypes = EnumSet.allOf(ShopType.class);
    private boolean hideEmpty = false;
    private boolean hideFull = false;
    private final EnumMap<ShopAttribute, SortMeta> sort = new EnumMap<>(ShopAttribute.class);

    private List<FoundShop> results = List.of();
    private int page = 0;

    public FindState(ItemStack itemStack, String itemKey, String itemDisplayName,
                     String queryWorld, int qx, int qy, int qz, boolean hasPosition) {
        this.itemStack = itemStack;
        this.itemKey = itemKey;
        this.itemDisplayName = itemDisplayName;
        this.queryWorld = queryWorld;
        this.qx = qx;
        this.qy = qy;
        this.qz = qz;
        this.hasPosition = hasPosition;
        reset();
    }

    /** Restore defaults: every shop type, every attribute selected ascending at weight 0, no hides. */
    public void reset() {
        shopTypes.clear();
        shopTypes.addAll(EnumSet.allOf(ShopType.class));
        sort.clear();
        for (ShopAttribute attribute : ShopAttribute.values()) {
            sort.put(attribute, new SortMeta(SortDirection.ASCENDING, 0));
        }
        hideEmpty = false;
        hideFull = false;
    }

    // ── identity / position ────────────────────────────────────────────────────

    public ItemStack itemStack() { return itemStack; }
    public String itemKey() { return itemKey; }
    public String itemDisplayName() { return itemDisplayName; }
    public String queryWorld() { return queryWorld; }
    public boolean hasPosition() { return hasPosition; }
    public int qx() { return qx; }
    public int qy() { return qy; }
    public int qz() { return qz; }

    // ── query / filters ────────────────────────────────────────────────────────

    public boolean fuzzy() { return fuzzy; }
    public void setFuzzy(boolean fuzzy) { this.fuzzy = fuzzy; }

    public Set<ShopType> shopTypes() { return Collections.unmodifiableSet(shopTypes); }
    public void setShopTypes(Collection<ShopType> types) {
        shopTypes.clear();
        shopTypes.addAll(types);
    }

    public boolean hideEmpty() { return hideEmpty; }
    public void setHideEmpty(boolean v) { this.hideEmpty = v; }
    public boolean hideFull() { return hideFull; }
    public void setHideFull(boolean v) { this.hideFull = v; }

    // ── sorting ────────────────────────────────────────────────────────────────

    public Map<ShopAttribute, SortMeta> sort() { return Collections.unmodifiableMap(sort); }

    public SortMeta sortMeta(ShopAttribute attribute) { return sort.get(attribute); }

    /** Select (or update) an attribute for sorting. */
    public void select(ShopAttribute attribute, SortDirection direction, int weight) {
        sort.put(attribute, new SortMeta(direction, clampWeight(weight)));
    }

    public void setDirection(ShopAttribute attribute, SortDirection direction) {
        SortMeta meta = sort.get(attribute);
        if (meta != null) meta.direction(direction);
    }

    public void setWeight(ShopAttribute attribute, int weight) {
        SortMeta meta = sort.get(attribute);
        if (meta != null) meta.weight(clampWeight(weight));
    }

    public void deselect(ShopAttribute attribute) { sort.remove(attribute); }

    private static int clampWeight(int w) { return Math.max(0, Math.min(100, w)); }

    // ── quick-action presets (ported from the legacy FindDialog) ────────────────

    public void presetBuyCheap() {
        setShopTypes(List.of(ShopType.BUY, ShopType.BOTH));
        select(ShopAttribute.UNIT_BUY_PRICE, SortDirection.ASCENDING, 100);
        select(ShopAttribute.DISTANCE, SortDirection.ASCENDING, 99);
        hideEmpty = true;
    }

    public void presetBuyNearby() {
        setShopTypes(List.of(ShopType.BUY, ShopType.BOTH));
        select(ShopAttribute.DISTANCE, SortDirection.ASCENDING, 100);
        select(ShopAttribute.UNIT_BUY_PRICE, SortDirection.ASCENDING, 99);
        hideEmpty = true;
    }

    public void presetSellBest() {
        setShopTypes(List.of(ShopType.SELL, ShopType.BOTH));
        select(ShopAttribute.UNIT_SELL_PRICE, SortDirection.DESCENDING, 100);
        select(ShopAttribute.REMAINING_CAPACITY, SortDirection.DESCENDING, 99);
        select(ShopAttribute.DISTANCE, SortDirection.ASCENDING, 98);
        hideFull = true;
    }

    // ── the pipeline (pure; unit-tested) ────────────────────────────────────────

    /**
     * Apply the in-memory pipeline to the raw rows from the registry: hide-full,
     * hide-empty, weighted multi-key sort, then the shop-type filter — the same
     * order as the legacy {@code applyToStream}.
     */
    public List<FoundShop> pipeline(List<FoundShop> shops) {
        return applyShopTypeFilter(
                applySort(
                        applyHideEmpty(
                                applyHideFull(shops.stream()))))
                .toList();
    }

    private Stream<FoundShop> applyHideFull(Stream<FoundShop> stream) {
        if (!hideFull) return stream;
        // null capacity = admin/infinite → always has room.
        return stream.filter(s -> s.capacity() == null || s.capacity() > 0);
    }

    private Stream<FoundShop> applyHideEmpty(Stream<FoundShop> stream) {
        if (!hideEmpty) return stream;
        // null stock = admin/infinite → never empty.
        return stream.filter(s -> s.stock() == null || s.stock() > 0);
    }

    private Stream<FoundShop> applySort(Stream<FoundShop> stream) {
        Comparator<FoundShop> comparator = buildComparator();
        return comparator == null ? stream : stream.sorted(comparator);
    }

    private Stream<FoundShop> applyShopTypeFilter(Stream<FoundShop> stream) {
        if (shopTypes.isEmpty()) return Stream.empty();
        if (shopTypes.size() < ShopType.values().length) {
            return stream.filter(s -> shopTypes.contains(s.shopType()));
        }
        return stream;
    }

    /** Compose the selected attributes' comparators by descending weight (stable in enum order). */
    private Comparator<FoundShop> buildComparator() {
        List<Map.Entry<ShopAttribute, SortMeta>> entries = new ArrayList<>(sort.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<ShopAttribute, SortMeta> e) -> e.getValue().weight())
                .reversed());
        Comparator<FoundShop> comparator = null;
        for (Map.Entry<ShopAttribute, SortMeta> e : entries) {
            Comparator<FoundShop> next = e.getKey().comparator(e.getValue().direction());
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        return comparator;
    }

    // ── results / paging ────────────────────────────────────────────────────────

    public void setResults(List<FoundShop> results) {
        this.results = List.copyOf(results);
        this.page = 0;
    }

    public List<FoundShop> results() { return results; }
    public boolean isEmpty() { return results.isEmpty(); }
    public int page() { return page; }

    public int pageCount() {
        return Math.max(1, (results.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    public void nextPage() {
        if (page < pageCount() - 1) page++;
    }

    public void previousPage() {
        if (page > 0) page--;
    }

    /** The shops shown on the current page. */
    public List<FoundShop> pageShops() {
        int from = page * PAGE_SIZE;
        if (from >= results.size()) return List.of();
        int to = Math.min(from + PAGE_SIZE, results.size());
        return results.subList(from, to);
    }
}
