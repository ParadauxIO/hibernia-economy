package io.paradaux.chestshop.dialogs;

import com.google.inject.Inject;
import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.services.PurchasePreviewService;
import io.paradaux.hibernia.framework.curator.MenuFlow;
import io.paradaux.hibernia.framework.curator.MenuItem;
import io.paradaux.hibernia.framework.curator.MenuView;
import io.paradaux.hibernia.framework.curator.annotations.Action;
import io.paradaux.hibernia.framework.curator.annotations.Model;
import io.paradaux.hibernia.framework.curator.annotations.Screen;
import io.paradaux.hibernia.framework.curator.spi.MenuHandler;
import io.paradaux.hibernia.framework.i18n.Message;
import java.util.Arrays;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Display copies only: Curator blocks inventory transfers and owns close/session handling. */
public final class PurchasePreviewMenu implements MenuHandler {
    private final PurchasePreviewService previews;
    private final EconomyService economy;
    private final Message message;

    @Inject
    public PurchasePreviewMenu(PurchasePreviewService previews, EconomyService economy, Message message) {
        this.previews = previews;
        this.economy = economy;
        this.message = message;
    }

    @Screen
    public MenuView preview(@Model PurchasePreviewService.Offer offer, MenuFlow flow) {
        var stock = offer.stock();
        var view = MenuView.chest(message.component(offer.buying() ? "shop.purchase.title" : "shop.sale.title"), 6)
                .content(0, 44, Arrays.stream(stock).map(MenuItem::display).toList())
                .slot(
                        49,
                        MenuItem.button(
                                button(
                                        Material.LIME_WOOL,
                                        "shop.purchase.confirm",
                                        "quantity",
                                        Arrays.stream(stock)
                                                .mapToInt(ItemStack::getAmount)
                                                .sum(),
                                        "price",
                                        economy.format(offer.price())),
                                "confirm",
                                null))
                .slot(45, MenuItem.button(button(Material.BARRIER, "shop.purchase.cancel"), "cancel", null));
        if (flow.page() > 0)
            view.slot(48, MenuItem.button(button(Material.ARROW, "find.button.prev"), "previous", null));
        if ((flow.page() + 1) * 45 < stock.length)
            view.slot(50, MenuItem.button(button(Material.ARROW, "find.button.next"), "next", null));
        return view.build();
    }

    @Action("confirm")
    public void confirm(@Model PurchasePreviewService.Offer offer, MenuFlow flow) {
        var player = flow.player();
        flow.close();
        previews.confirm(offer, player);
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

    private ItemStack button(Material material, String key, Object... args) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(message.component(key, args)));
        return item;
    }
}
