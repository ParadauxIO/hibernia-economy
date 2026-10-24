package io.paradaux.chestshop.dialogs;

import com.google.inject.Inject;
import io.paradaux.chestshop.services.ShopVariantsService;
import io.paradaux.hibernia.framework.curator.*;
import io.paradaux.hibernia.framework.curator.annotations.Action;
import io.paradaux.hibernia.framework.curator.annotations.Model;
import io.paradaux.hibernia.framework.curator.annotations.Screen;
import io.paradaux.hibernia.framework.curator.spi.MenuHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class ShopVariantsMenu implements MenuHandler {
    private final ShopVariantsService variants;
    private final com.google.inject.Provider<MenuManager> menus;
    private final Message message;

    @Inject
    public ShopVariantsMenu(
            ShopVariantsService variants, com.google.inject.Provider<MenuManager> menus, Message message) {
        this.variants = variants;
        this.menus = menus;
        this.message = message;
    }

    @Screen
    public MenuView choices(@Model ShopVariantsService.Selection selection, MenuFlow flow) {
        var view = MenuView.chest(message.component("shop.variants.title"), 6)
                .slot(45, MenuItem.button(button(Material.BARRIER, "shop.purchase.cancel"), "cancel", null));
        if (selection.variants().isEmpty()) {
            view.slot(22, MenuItem.display(button(Material.BARRIER, "shop.variants.empty")));
        } else {
            view.content(
                    0,
                    44,
                    selection.variants().stream()
                            .map(item -> MenuItem.button(item.clone(), "choose", item.clone()))
                            .toList());
        }
        if (flow.page() > 0)
            view.slot(48, MenuItem.button(button(Material.ARROW, "find.button.prev"), "previous", null));
        if ((flow.page() + 1) * 45 < selection.variants().size())
            view.slot(50, MenuItem.button(button(Material.ARROW, "find.button.next"), "next", null));
        return view.build();
    }

    @Action("choose")
    public void choose(MenuContext click, @Model ShopVariantsService.Selection selection, MenuFlow flow) {
        var offer = variants.preview(selection, click.player(), click.payload(ItemStack.class));
        if (offer == null) {
            flow.close();
            return;
        }
        flow.close();
        menus.get().open(click.player(), PurchasePreviewMenu.class, offer);
    }

    @Action("cancel")
    public void cancel(MenuFlow flow) {
        flow.close();
    }

    @Action("next")
    public void next(MenuFlow flow) {
        flow.nextPage();
    }

    @Action("previous")
    public void previous(MenuFlow flow) {
        flow.previousPage();
    }

    private ItemStack button(Material material, String key) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(message.component(key)));
        return item;
    }
}
