package io.paradaux.chestshop.dialogs;

import com.google.inject.Inject;
import io.paradaux.chestshop.model.FoundShop;
import io.paradaux.chestshop.model.ShopItem;
import io.paradaux.chestshop.services.FindPresentation;
import io.paradaux.chestshop.services.ShopDisplayService;
import io.paradaux.hibernia.framework.curator.MenuContext;
import io.paradaux.hibernia.framework.curator.MenuFlow;
import io.paradaux.hibernia.framework.curator.MenuItem;
import io.paradaux.hibernia.framework.curator.MenuView;
import io.paradaux.hibernia.framework.curator.annotations.Action;
import io.paradaux.hibernia.framework.curator.annotations.Model;
import io.paradaux.hibernia.framework.curator.annotations.Screen;
import io.paradaux.hibernia.framework.curator.spi.MenuHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** ChestShopDatabase's 45-result chest layout and item preview, shared by Java and Bedrock. */
public final class FindResultsMenu implements MenuHandler {
    private final FindPresentation presentation;
    private final ShopDisplayService displays;
    private final Message message;

    @Inject
    public FindResultsMenu(FindPresentation presentation, ShopDisplayService displays, Message message) {
        this.presentation = presentation;
        this.displays = displays;
        this.message = message;
    }

    /** Preview data is isolated to one search; Curator owns the page and back stack. */
    public static final class State {
        private final FindState query;
        private final Map<String, ItemStack> previews = new HashMap<>();
        private ItemStack preview;

        public State(FindState query) {
            this.query = query;
            if (query.itemStack() != null)
                previews.put(query.itemKey(), query.itemStack().clone());
        }
    }

    @Screen
    public MenuView results(@Model State state, MenuFlow flow) {
        FindState query = state.query;
        var view = MenuView.chest(message.component("find.results.menu-title"), 6)
                .content(
                        0,
                        44,
                        query.results().stream()
                                .map(shop -> MenuItem.button(icon(shop, query), "shop", shop))
                                .toList())
                .slot(45, MenuItem.button(button(Material.ARROW, "find.button.back"), "back", null));
        ItemStack summary = query.itemStack() == null
                ? new ItemStack(Material.PAPER)
                : query.itemStack().clone();
        summary.editMeta(meta -> {
            var lore = new java.util.ArrayList<Component>();
            if (meta.lore() != null) lore.addAll(meta.lore());
            lore.add(message.component(
                            "find.results.summary",
                            "count",
                            query.results().size(),
                            "page",
                            flow.page() + 1,
                            "pages",
                            Math.max(1, (query.results().size() + 44) / 45))
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
        });
        view.slot(49, MenuItem.display(summary));
        if (flow.page() > 0)
            view.slot(48, MenuItem.button(button(Material.PAPER, "find.button.prev"), "previous", null));
        if ((long) (flow.page() + 1) * 45 < query.results().size()) {
            view.slot(50, MenuItem.button(button(Material.PAPER, "find.button.next"), "next", null));
        }
        return view.fill(MenuItem.display(filler())).build();
    }

    private ItemStack icon(FoundShop shop, FindState query) {
        ItemStack icon = presentation.icon(shop);
        if (icon == null) icon = new ItemStack(Material.CHEST);
        icon.editMeta(meta -> {
            meta.displayName(presentation.title(shop, displays).decoration(TextDecoration.ITALIC, false));
            meta.lore(presentation.lore(shop, displays).stream()
                    .map(line -> line.decoration(TextDecoration.ITALIC, false))
                    .toList());
            if (query.fuzzy() && shop.isSimilarTo(query.itemKey())) meta.setEnchantmentGlintOverride(true);
        });
        return icon;
    }

    @Action("shop")
    public void shop(MenuContext click, @Model State state, MenuFlow flow) {
        FoundShop shop = click.payload(FoundShop.class);
        boolean left = click.click().isLeftClick();
        // Match the original: shift-right also uses GPS when configured; otherwise inspect.
        if (!left && presentation.command(shop, false).isBlank()) {
            ItemStack item = state.previews.computeIfAbsent(
                    shop.itemKey(), ignored -> ShopItem.decode(shop.material(), shop.itemData()));
            if (item == null || item.getType().isAir()) return;
            state.preview = item;
            flow.open("preview");
            return;
        }
        if (!presentation.command(shop, left).isBlank()) {
            flow.close();
            presentation.navigate(click.player(), shop, left);
        }
    }

    @Screen("preview")
    public MenuView preview(@Model State state) {
        return MenuView.chest(message.component("find.preview.menu-title"), 3)
                .slot(13, MenuItem.display(state.preview))
                .slot(18, MenuItem.button(button(Material.ARROW, "find.button.back"), "back", null))
                .fill(MenuItem.display(filler()))
                .backOnClose(true)
                .build();
    }

    @Action("next")
    public void next(MenuFlow flow) {
        flow.nextPage();
    }

    @Action("previous")
    public void previous(MenuFlow flow) {
        flow.previousPage();
    }

    @Action("back")
    public void back(MenuFlow flow) {
        flow.back();
    }

    private ItemStack button(Material material, String key) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(message.component(key).decoration(TextDecoration.ITALIC, false)));
        return item;
    }

    private static ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        item.editMeta(meta -> meta.displayName(Component.empty()));
        return item;
    }
}
