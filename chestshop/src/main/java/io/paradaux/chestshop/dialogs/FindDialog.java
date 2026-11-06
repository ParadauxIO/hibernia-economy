package io.paradaux.chestshop.dialogs;

import com.google.inject.Inject;
import io.paradaux.chestshop.model.ShopAttribute;
import io.paradaux.chestshop.model.ShopType;
import io.paradaux.chestshop.model.SortDirection;
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
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * The {@code /find} shop-search dialog flow — the first Usher consumer in
 * ChestShop. A faithful port of the legacy chestshop-database find dialog: a main
 * screen with quick-actions + a fuzzy toggle, Filters and Sort sub-screens that
 * mutate the shared {@link FindState}, then Curator chest results for all players. The query
 * runs off-thread via {@link DialogFlow#await} with a wait-screen.
 */
@Dialog("find")
public final class FindDialog implements DialogHandler {

    private final ShopFinderService finder;
    private final Message message;
    private final io.paradaux.hibernia.framework.curator.MenuManager menus;

    @Inject
    public FindDialog(
            ShopFinderService finder, Message message, io.paradaux.hibernia.framework.curator.MenuManager menus) {
        this.finder = finder;
        this.message = message;
        this.menus = menus;
    }

    // ── main screen ─────────────────────────────────────────────────────────────

    @Screen
    public DialogView main(@Model FindState state) {
        DialogView.Builder b = DialogView.multiAction(Text.key("find.title", "item", state.itemDisplayName()))
                .canCloseWithEscape(true);
        if (state.itemStack() != null) {
            // The dialog codec rejects damageable items with a stack limit above one.
            // Sanitize only the preview; search identity and the player's item stay intact.
            var preview = state.itemStack().asOne();
            var meta = preview.getItemMeta();
            if (meta instanceof org.bukkit.inventory.meta.Damageable damageable
                    && (damageable.hasMaxDamage() || preview.getType().getMaxDurability() > 0)
                    && preview.getMaxStackSize() > 1) {
                meta.setMaxStackSize(1);
                preview.setItemMeta(meta);
            }
            b.bodyItem(preview);
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
            Player player = f.player();
            f.close();
            menus.open(player, FindResultsMenu.class, new FindResultsMenu.State(state));
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
}
