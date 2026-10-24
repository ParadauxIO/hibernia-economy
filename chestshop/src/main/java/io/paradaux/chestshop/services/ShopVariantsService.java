package io.paradaux.chestshop.services;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.ItemStack;

@Singleton
public final class ShopVariantsService {
    private final WildcardItems wildcards;
    private final ShopBlockService shops;
    private final ChestShopConfiguration config;
    private final PurchasePreviewService previews;

    @Inject
    public ShopVariantsService(
            WildcardItems wildcards,
            ShopBlockService shops,
            ChestShopConfiguration config,
            PurchasePreviewService previews) {
        this.wildcards = wildcards;
        this.shops = shops;
        this.config = config;
        this.previews = previews;
    }

    public record Selection(Block block, String pattern, Action action, List<ItemStack> variants) {}

    public Selection choices(Sign sign, Player player, Action action) {
        if (!previews.canUse(sign, player)) return null;
        String pattern = SignService.getItem(sign);
        if (!WildcardItems.isPattern(pattern)) return null;
        Action buy = config.isReverseButtons() ? Action.LEFT_CLICK_BLOCK : Action.RIGHT_CLICK_BLOCK;
        var container = shops.findConnectedContainer(sign);
        var source = action == buy ? (container == null ? null : container.getInventory()) : player.getInventory();
        return new Selection(sign.getBlock(), pattern, action, wildcards.choices(pattern, source));
    }

    public PurchasePreviewService.Offer preview(Selection selection, Player player, ItemStack selected) {
        if (!(selection.block().getState() instanceof Sign sign)
                || !selection.pattern().equals(SignService.getItem(sign))
                || selection.variants().stream().noneMatch(item -> item.isSimilar(selected))) return null;
        return previews.preview(sign, player, selection.action(), selected);
    }
}
