package io.paradaux.chestshop.dialogs;

import com.google.inject.Inject;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.chestshop.services.PurchasePreviewService;
import io.paradaux.hibernia.framework.curator.MenuManager;
import io.paradaux.hibernia.framework.exceptions.BadCommandException;
import io.paradaux.hibernia.framework.usher.*;
import io.paradaux.hibernia.framework.usher.annotations.*;
import io.paradaux.hibernia.framework.usher.input.DialogInputSpec;
import io.paradaux.hibernia.framework.usher.spi.DialogHandler;

/** Quantity changes always return to a fresh preview before any money or items move. */
@Dialog("shop-quantity")
public final class PurchaseQuantityDialog implements DialogHandler {
    private final PurchasePreviewService previews;
    private final MenuManager menus;
    private final ChestShopConfiguration config;

    @Inject
    public PurchaseQuantityDialog(PurchasePreviewService previews, MenuManager menus, ChestShopConfiguration config) {
        this.previews = previews;
        this.menus = menus;
        this.config = config;
    }

    @Screen
    public DialogView main(@Model PurchasePreviewService.Offer offer) {
        var view = DialogView.multiAction("shop.quantity.change")
                .input(DialogInputSpec.text("amount", Text.key("shop.quantity.count")))
                .button("shop.quantity.custom", "custom")
                .button(Text.key("shop.quantity.multiple", "multiple", 1, "quantity", offer.bundleQuantity()), "one");
        if ((long) offer.bundleQuantity() * 2 <= config.getMaxShopAmount())
            view.button(
                    Text.key("shop.quantity.multiple", "multiple", 2, "quantity", offer.bundleQuantity() * 2), "two");
        if ((long) offer.bundleQuantity() * 4 <= config.getMaxShopAmount())
            view.button(
                    Text.key("shop.quantity.multiple", "multiple", 4, "quantity", offer.bundleQuantity() * 4), "four");
        if ((long) offer.bundleQuantity() * 8 <= config.getMaxShopAmount())
            view.button(
                    Text.key("shop.quantity.multiple", "multiple", 8, "quantity", offer.bundleQuantity() * 8), "eight");
        return view.button("shop.quantity.back", "back").columns(2).build();
    }

    @Action("custom")
    public void custom(@Input("amount") String amount, @Model PurchasePreviewService.Offer offer, DialogFlow flow) {
        int count;
        try {
            count = Integer.parseInt(amount == null ? "" : amount.trim());
        } catch (NumberFormatException e) {
            throw new BadCommandException("shop.quantity.invalid", "max", config.getMaxShopAmount());
        }
        if (count < 1 || count > config.getMaxShopAmount())
            throw new BadCommandException("shop.quantity.invalid", "max", config.getMaxShopAmount());
        resize(offer, flow, count);
    }

    @Action("one")
    public void one(@Model PurchasePreviewService.Offer offer, DialogFlow flow) {
        resize(offer, flow, offer.bundleQuantity());
    }

    @Action("two")
    public void two(@Model PurchasePreviewService.Offer offer, DialogFlow flow) {
        resize(offer, flow, (long) offer.bundleQuantity() * 2);
    }

    @Action("four")
    public void four(@Model PurchasePreviewService.Offer offer, DialogFlow flow) {
        resize(offer, flow, (long) offer.bundleQuantity() * 4);
    }

    @Action("eight")
    public void eight(@Model PurchasePreviewService.Offer offer, DialogFlow flow) {
        resize(offer, flow, (long) offer.bundleQuantity() * 8);
    }

    @Action("back")
    public void back(@Model PurchasePreviewService.Offer offer, DialogFlow flow) {
        var player = flow.player();
        flow.close();
        menus.open(player, PurchasePreviewMenu.class, offer);
    }

    private void resize(PurchasePreviewService.Offer offer, DialogFlow flow, long amount) {
        if (amount < 1 || amount > config.getMaxShopAmount())
            throw new BadCommandException("shop.quantity.invalid", "max", config.getMaxShopAmount());
        var player = flow.player();
        var updated = previews.resize(offer, player, (int) amount);
        flow.close();
        if (updated != null) menus.open(player, PurchasePreviewMenu.class, updated);
    }
}
