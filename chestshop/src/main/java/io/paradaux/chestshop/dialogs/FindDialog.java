package io.paradaux.chestshop.dialogs;

import com.google.inject.Inject;
import io.paradaux.chestshop.model.FoundShop;
import io.paradaux.chestshop.model.ShopAttribute;
import io.paradaux.chestshop.model.ShopItem;
import io.paradaux.chestshop.model.ShopType;
import io.paradaux.chestshop.model.SortDirection;
import io.paradaux.chestshop.services.ShopDisplayService;
import io.paradaux.chestshop.services.ShopFinderService;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.hibernia.framework.usher.ButtonSpec;
import io.paradaux.hibernia.framework.usher.DialogContext;
import io.paradaux.hibernia.framework.usher.DialogFlow;
import io.paradaux.hibernia.framework.usher.DialogView;
import io.paradaux.hibernia.framework.usher.Text;
import io.paradaux.hibernia.framework.usher.annotations.Action;
import io.paradaux.hibernia.framework.usher.annotations.Dialog;
import io.paradaux.hibernia.framework.usher.annotations.Input;
import io.paradaux.hibernia.framework.usher.annotations.Model;
import io.paradaux.hibernia.framework.usher.annotations.Screen;
import io.paradaux.hibernia.framework.usher.input.DialogInputSpec;
import io.paradaux.hibernia.framework.usher.spi.DialogHandler;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The {@code /find} shop-search dialog flow — the first Usher consumer in
 * ChestShop. A faithful port of the legacy chestshop-database find dialog: a main
 * screen with quick-actions + a fuzzy toggle, Filters and Sort sub-screens that
 * mutate the shared {@link FindState}, and a paginated results list. The query
 * runs off-thread via {@link DialogFlow#await} with a wait-screen.
 */
@Dialog("find")
public final class FindDialog implements DialogHandler {

    private static final DecimalFormat MONEY = new DecimalFormat("$#,##0.00");

    private final io.paradaux.chestshop.services.FindPresentation presentation;
    private final io.paradaux.chestshop.dialogs.FindResultsMenu menus;
    private final ShopFinderService finder;
    private final Message message;
    private final ShopDisplayService displays;

    public FindDialog(ShopFinderService finder, Message message, ShopDisplayService displays) {
        this(finder, message, displays, new io.paradaux.chestshop.services.FindPresentation());
    }

    public FindDialog(
            ShopFinderService finder,
            Message message,
            ShopDisplayService displays,
            io.paradaux.chestshop.services.FindPresentation presentation) {
        this(finder, message, displays, presentation, null);
    }

    @Inject
    public FindDialog(
            ShopFinderService finder,
            Message message,
            ShopDisplayService displays,
            io.paradaux.chestshop.services.FindPresentation presentation,
            io.paradaux.chestshop.dialogs.FindResultsMenu menus) {
        this.menus = menus;
        this.presentation = presentation;
        this.displays = displays;
        this.finder = finder;
        this.message = message;
    }

    // ── main screen ─────────────────────────────────────────────────────────────

    @Screen
    public DialogView main(@Model FindState state) {
        DialogView.Builder b = DialogView.multiAction(Text.key("find.title", "item", state.itemDisplayName()))
                .canCloseWithEscape(true);
        if (state.itemStack() != null) {
            b.bodyItem(state.itemStack());
        }
        return b.toggle("fuzzy", "find.toggle.fuzzy", "find.opt.on", "find.opt.off", state.fuzzy())
                .button(Text.key("find.button.buy-cheap"), "buyCheap")
                .button(Text.key("find.button.buy-nearby"), "buyNearby")
                .button(Text.key("find.button.sell-best"), "sellBest")
                .open(Text.key("find.button.filters"), "filters")
                .open(Text.key("find.button.sorting"), "sort")
                .button(Text.key("find.button.search"), "submit")
                .exit("find.button.close")
                .columns(1)
                .build();
    }

    @Action("submit")
    public void submit(@Input("fuzzy") boolean fuzzy, @Model FindState state, DialogFlow flow) {
        state.setFuzzy(fuzzy);
        runSearch(state, flow);
    }

    @Action("buyCheap")
    public void buyCheap(@Input("fuzzy") boolean fuzzy, @Model FindState state, DialogFlow flow) {
        state.setFuzzy(fuzzy);
        state.presetBuyCheap();
        runSearch(state, flow);
    }

    @Action("buyNearby")
    public void buyNearby(@Input("fuzzy") boolean fuzzy, @Model FindState state, DialogFlow flow) {
        state.setFuzzy(fuzzy);
        state.presetBuyNearby();
        runSearch(state, flow);
    }

    @Action("sellBest")
    public void sellBest(@Input("fuzzy") boolean fuzzy, @Model FindState state, DialogFlow flow) {
        state.setFuzzy(fuzzy);
        state.presetSellBest();
        runSearch(state, flow);
    }

    private void runSearch(FindState state, DialogFlow flow) {
        if (!finder.available()) {
            message.send(flow.player(), "find.no-search");
            flow.close();
            return;
        }
        flow.await(finder.find(state), Text.key("find.querying"), (results, f) -> {
            state.setResults(results);
            if (results.isEmpty()) {
                message.send(f.player(), "find.empty");
                f.close();
                return;
            }
            if (f.isBedrockViewer() && menus != null) {
                f.close();
                menus.open(f.player(), state);
            } else {
                f.open("results");
            }
        });
    }

    // ── filters screen ──────────────────────────────────────────────────────────

    @Screen("filters")
    public DialogView filters(@Model FindState state) {
        return DialogView.multiAction(Text.key("find.filters.title"))
                .toggle(
                        "type_buy",
                        "find.filter.buy",
                        "find.opt.on",
                        "find.opt.off",
                        state.shopTypes().contains(ShopType.BUY))
                .toggle(
                        "type_sell",
                        "find.filter.sell",
                        "find.opt.on",
                        "find.opt.off",
                        state.shopTypes().contains(ShopType.SELL))
                .toggle(
                        "type_both",
                        "find.filter.both",
                        "find.opt.on",
                        "find.opt.off",
                        state.shopTypes().contains(ShopType.BOTH))
                .toggle("show_empty", "find.filter.show-empty", "find.opt.on", "find.opt.off", !state.hideEmpty())
                .toggle("show_full", "find.filter.show-full", "find.opt.on", "find.opt.off", !state.hideFull())
                .button(Text.key("find.button.save"), "applyFilters")
                .exit(ButtonSpec.back(Text.key("find.button.back")))
                .columns(1)
                .build();
    }

    @Action("applyFilters")
    public void applyFilters(
            @Input("type_buy") boolean buy,
            @Input("type_sell") boolean sell,
            @Input("type_both") boolean both,
            @Input("show_empty") boolean showEmpty,
            @Input("show_full") boolean showFull,
            @Model FindState state,
            DialogFlow flow) {
        List<ShopType> types = new ArrayList<>();
        if (buy) types.add(ShopType.BUY);
        if (sell) types.add(ShopType.SELL);
        if (both) types.add(ShopType.BOTH);
        state.setShopTypes(types);
        state.setHideEmpty(!showEmpty);
        state.setHideFull(!showFull);
        flow.back();
    }

    // ── sort screen ─────────────────────────────────────────────────────────────

    @Screen("sort")
    public DialogView sort(@Model FindState state) {
        DialogView.Builder b = DialogView.multiAction(Text.key("find.sort.title"));
        for (ShopAttribute attribute : ShopAttribute.values()) {
            FindState.SortMeta meta = state.sortMeta(attribute);
            b.option(
                    "dir_" + attribute.name(),
                    Text.of(Component.text(attribute.displayName())),
                    directionOptions(meta));
            int weight = meta != null ? meta.weight() : 0;
            b.number(
                    "pri_" + attribute.name(),
                    Text.key("find.sort.priority", "attr", attribute.displayName()),
                    0,
                    100,
                    1f,
                    (float) weight);
        }
        return b.button(Text.key("find.button.save"), "applySort")
                .exit(ButtonSpec.back(Text.key("find.button.back")))
                .columns(1)
                .build();
    }

    private static List<DialogInputSpec.OptionSpec> directionOptions(FindState.SortMeta meta) {
        boolean off = meta == null;
        boolean asc = meta != null && meta.direction() == SortDirection.ASCENDING;
        boolean desc = meta != null && meta.direction() == SortDirection.DESCENDING;
        return List.of(
                new DialogInputSpec.OptionSpec("off", Text.key("find.sort.off"), off),
                new DialogInputSpec.OptionSpec("asc", Text.key("find.sort.asc"), asc),
                new DialogInputSpec.OptionSpec("desc", Text.key("find.sort.desc"), desc));
    }

    @Action("applySort")
    public void applySort(@Model FindState state, DialogContext ctx, DialogFlow flow) {
        for (ShopAttribute attribute : ShopAttribute.values()) {
            String dir = ctx.text("dir_" + attribute.name());
            if (dir == null || dir.equals("off")) {
                state.deselect(attribute);
                continue;
            }
            SortDirection direction = dir.equals("desc") ? SortDirection.DESCENDING : SortDirection.ASCENDING;
            Float priority = ctx.number("pri_" + attribute.name());
            state.select(attribute, direction, priority == null ? 0 : Math.round(priority));
        }
        flow.back();
    }

    // ── results screen ──────────────────────────────────────────────────────────

    @Screen("results")
    public DialogView results(@Model FindState state) {
        List<FoundShop> shops = state.pageShops();
        String queriedKey = state.fuzzy() ? state.itemKey() : null;

        DialogView.Builder b = DialogView.multiAction(Text.key(
                        "find.results.title",
                        "item",
                        state.itemDisplayName(),
                        "page",
                        state.page() + 1,
                        "pages",
                        state.pageCount(),
                        "count",
                        state.results().size()))
                .canCloseWithEscape(true);

        for (int i = 0; i < shops.size(); i++) {
            FoundShop shop = shops.get(i);
            Component label = shopLabel(shop, queriedKey);
            b.body(Text.of(presentation.details(shop, displays)));
            ItemStack icon = presentation.icon(shop);
            if (icon == null) icon = ShopItem.decode(shop.material(), shop.itemData());
            if (icon != null) {
                b.body(Text.of(label));
                b.bodyItem(icon);
            }
            b.button(Text.of(label), "select" + i);
            if (!presentation.command(shop, false).isBlank()) b.button(Text.key("find.button.gps"), "gps" + i);
            b.button(Text.key("find.button.inspect"), "inspect" + i);
        }
        if (state.page() > 0) {
            b.button(Text.key("find.button.prev"), "prevPage");
        }
        if (state.page() < state.pageCount() - 1) {
            b.button(Text.key("find.button.next"), "nextPage");
        }
        return b.exit("find.button.close").columns(1).build();
    }

    @Action("prevPage")
    public void prevPage(@Model FindState state, DialogFlow flow) {
        state.previousPage();
        flow.refresh();
    }

    @Action("nextPage")
    public void nextPage(@Model FindState state, DialogFlow flow) {
        state.nextPage();
        flow.refresh();
    }

    // One @Action per page slot — dialog buttons map to named actions, so a fixed
    // slot set is how a results list offers per-row selection.
    @Action("select0")
    public void select0(@Model FindState s, DialogFlow f) {
        select(s, f, 0);
    }

    @Action("select1")
    public void select1(@Model FindState s, DialogFlow f) {
        select(s, f, 1);
    }

    @Action("select2")
    public void select2(@Model FindState s, DialogFlow f) {
        select(s, f, 2);
    }

    @Action("select3")
    public void select3(@Model FindState s, DialogFlow f) {
        select(s, f, 3);
    }

    @Action("select4")
    public void select4(@Model FindState s, DialogFlow f) {
        select(s, f, 4);
    }

    @Action("select5")
    public void select5(@Model FindState s, DialogFlow f) {
        select(s, f, 5);
    }

    @Action("select6")
    public void select6(@Model FindState s, DialogFlow f) {
        select(s, f, 6);
    }

    private void select(FindState state, DialogFlow flow, int slot) {
        List<FoundShop> shops = state.pageShops();
        if (slot >= shops.size()) {
            return;
        }
        FoundShop shop = shops.get(slot);
        Player player = flow.player();
        message.send(
                player,
                "find.selected",
                "owner",
                shop.ownerName() != null ? shop.ownerName() : "Admin Shop",
                "world",
                shop.world(),
                "x",
                shop.x(),
                "y",
                shop.y(),
                "z",
                shop.z());
        presentation.navigate(player, shop, true);
        flow.close();
    }

    @Action("gps0")
    public void gps0(@Model FindState s, DialogFlow f) {
        gps(s, f, 0);
    }

    @Action("gps1")
    public void gps1(@Model FindState s, DialogFlow f) {
        gps(s, f, 1);
    }

    @Action("gps2")
    public void gps2(@Model FindState s, DialogFlow f) {
        gps(s, f, 2);
    }

    @Action("gps3")
    public void gps3(@Model FindState s, DialogFlow f) {
        gps(s, f, 3);
    }

    @Action("gps4")
    public void gps4(@Model FindState s, DialogFlow f) {
        gps(s, f, 4);
    }

    @Action("gps5")
    public void gps5(@Model FindState s, DialogFlow f) {
        gps(s, f, 5);
    }

    @Action("gps6")
    public void gps6(@Model FindState s, DialogFlow f) {
        gps(s, f, 6);
    }

    private void gps(FindState state, DialogFlow flow, int slot) {
        if (slot >= state.pageShops().size()) return;
        FoundShop shop = state.pageShops().get(slot);
        if (presentation.command(shop, false).isBlank()) {
            inspect(state, flow, slot);
        } else {
            presentation.navigate(flow.player(), shop, false);
            flow.close();
        }
    }

    @Screen("inspect")
    public DialogView inspection(@Model FindState state) {
        FoundShop shop = state.inspectedShop();
        DialogView.Builder view = DialogView.multiAction(Text.key("find.inspect.title", "item", shop.itemKey()))
                .body(Text.of(presentation.details(shop, displays)))
                .canCloseWithEscape(true);
        ItemStack item = ShopItem.decode(shop.material(), shop.itemData());
        if (item != null) view.bodyItem(item);
        return view.button(Text.key("find.button.back"), "inspectionBack").build();
    }

    private void inspect(FindState state, DialogFlow flow, int slot) {
        if (slot < state.pageShops().size()) {
            state.inspect(slot);
            flow.open("inspect");
        }
    }

    @Action("inspectionBack")
    public void inspectionBack(DialogFlow flow) {
        flow.back();
    }

    @Action("inspect0")
    public void inspect0(@Model FindState state, DialogFlow flow) {
        inspect(state, flow, 0);
    }

    @Action("inspect1")
    public void inspect1(@Model FindState state, DialogFlow flow) {
        inspect(state, flow, 1);
    }

    @Action("inspect2")
    public void inspect2(@Model FindState state, DialogFlow flow) {
        inspect(state, flow, 2);
    }

    @Action("inspect3")
    public void inspect3(@Model FindState state, DialogFlow flow) {
        inspect(state, flow, 3);
    }

    @Action("inspect4")
    public void inspect4(@Model FindState state, DialogFlow flow) {
        inspect(state, flow, 4);
    }

    @Action("inspect5")
    public void inspect5(@Model FindState state, DialogFlow flow) {
        inspect(state, flow, 5);
    }

    @Action("inspect6")
    public void inspect6(@Model FindState state, DialogFlow flow) {
        inspect(state, flow, 6);
    }

    // ── rendering helpers ───────────────────────────────────────────────────────

    private Component shopLabel(FoundShop shop, String queriedKey) {
        TextComponent.Builder b = Component.text();
        if (queriedKey != null && shop.isSimilarTo(queriedKey)) {
            b.append(Component.text("~ ", NamedTextColor.LIGHT_PURPLE));
        }
        String owner = shop.ownerName() != null ? shop.ownerName() : "Admin Shop";
        b.append(Component.text(owner, NamedTextColor.AQUA));
        b.append(sep());
        b.append(priceSummary(shop));
        b.append(sep());
        b.append(Component.text(
                stockText(shop) + " · qty " + shop.batchQty() + " · capacity "
                        + (shop.capacity() == null ? "∞" : shop.capacity()),
                NamedTextColor.GRAY));
        if (shop.distanceBlocks() >= 0) {
            b.append(sep());
            b.append(Component.text(shop.distanceBlocks() + "m", NamedTextColor.GRAY));
        }
        b.append(sep());
        b.append(Component.text(
                displays.location(shop.world(), shop.x(), shop.y(), shop.z()), NamedTextColor.DARK_GRAY));
        return b.build();
    }

    private static Component sep() {
        return Component.text("  ·  ", NamedTextColor.DARK_GRAY);
    }

    private static Component priceSummary(FoundShop shop) {
        // Presence-based, not shopType()-based: a registry row with BOTH prices null
        // classifies as BUY (shopType's fallback), and formatting a null price throws
        // "Cannot format given Object as a Number" and kills the whole results screen.
        // Render only the sides that actually have a price.
        boolean hasBuy = shop.buyPriceValue() != null;
        boolean hasSell = shop.sellPriceValue() != null;
        TextComponent.Builder b = Component.text();
        if (hasBuy) {
            b.append(buyPart(shop));
        }
        if (hasBuy && hasSell) {
            b.append(Component.text(" / ", NamedTextColor.DARK_GRAY));
        }
        if (hasSell) {
            b.append(sellPart(shop));
        }
        if (!hasBuy && !hasSell) {
            b.append(Component.text("no price", NamedTextColor.DARK_GRAY));
        }
        return b.build();
    }

    private static Component buyPart(FoundShop shop) {
        return Component.text(
                "Buy " + MONEY.format(shop.buyPriceValue()) + " (" + MONEY.format(shop.unitBuyPrice()) + "/ea)",
                NamedTextColor.GREEN);
    }

    private static Component sellPart(FoundShop shop) {
        return Component.text(
                "Sell " + MONEY.format(shop.sellPriceValue()) + " (" + MONEY.format(shop.unitSellPrice()) + "/ea)",
                NamedTextColor.GOLD);
    }

    private static String stockText(FoundShop shop) {
        return shop.stock() == null ? "∞ stock" : shop.stock() + " stock";
    }
}
