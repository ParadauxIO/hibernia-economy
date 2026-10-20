package io.paradaux.chestshop.dialogs;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.model.FoundShop;
import io.paradaux.chestshop.model.ShopItem;
import io.paradaux.chestshop.services.FindPresentation;
import io.paradaux.chestshop.services.ShopDisplayService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Inventory results retain Bedrock support and left/right navigation semantics. */
@Singleton
public final class FindResultsMenu implements Listener {
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final FindPresentation presentation;
    private final ShopDisplayService displays;

    @Inject
    public FindResultsMenu(
            org.bukkit.plugin.java.JavaPlugin plugin, FindPresentation presentation, ShopDisplayService displays) {
        this.plugin = plugin;
        this.presentation = presentation;
        this.displays = displays;
    }

    private static final class Page implements InventoryHolder {
        private final FindState state;
        private Inventory inventory;

        Page(FindState state) {
            this.state = state;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class Inspection implements InventoryHolder {
        private final FindState state;
        private Inventory inventory;

        Inspection(FindState state) {
            this.state = state;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private void inspect(Player player, FindState state, FoundShop shop) {
        Inspection inspection = new Inspection(state);
        inspection.inventory =
                Bukkit.createInventory(inspection, 27, Component.text("Item preview · " + shop.itemKey()));
        ItemStack item = ShopItem.decode(shop.material(), shop.itemData());
        if (item != null) inspection.inventory.setItem(13, item);
        inspection.inventory.setItem(22, button(Material.ARROW, "Back"));
        player.openInventory(inspection.inventory);
    }

    public void open(Player player, FindState state) {
        Page page = new Page(state);
        page.inventory = Bukkit.createInventory(
                page, 54, Component.text("Shop results · " + (state.page() + 1) + "/" + state.pageCount()));
        var shops = state.pageShops();
        for (int i = 0; i < shops.size(); i++) {
            FoundShop shop = shops.get(i);
            ItemStack icon = presentation.icon(shop);
            if (icon == null) icon = new ItemStack(Material.CHEST);
            icon.editMeta(meta -> {
                meta.displayName(presentation.title(shop, displays));
                var lore = new java.util.ArrayList<>(presentation.lore(shop, displays));
                lore.add(Component.text(
                        presentation.command(shop, false).isBlank()
                                ? "Left: transit · Right: inspect item"
                                : "Left: transit · Right: GPS · Shift-right: inspect item"));
                meta.lore(lore);
            });
            page.inventory.setItem(10 + i, icon);
        }
        page.inventory.setItem(45, button(Material.ARROW, "Previous"));
        page.inventory.setItem(49, button(Material.BARRIER, "Close"));
        page.inventory.setItem(53, button(Material.ARROW, "Next"));
        player.openInventory(page.inventory);
    }

    private static ItemStack button(Material material, String label) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(Component.text(label)));
        return item;
    }

    @EventHandler
    public void click(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Inspection inspection) {
            event.setCancelled(true);
            if (event.getWhoClicked() instanceof Player player
                    && event.getRawSlot() == 22
                    && (event.isLeftClick() || event.isRightClick())) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline() && player.getOpenInventory().getTopInventory() == inspection.inventory)
                        open(player, inspection.state);
                });
            }
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof Page page)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        boolean left = event.isLeftClick(), right = event.isRightClick(), shift = event.isShiftClick();
        // Bukkit inventory callbacks must defer view changes to the next tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory() == page.inventory)
                select(player, page, slot, left, right, shift);
        });
    }

    private void select(Player player, Page page, int slot, boolean left, boolean right, boolean shift) {
        if (slot >= 10 && slot < 10 + page.state.pageShops().size()) {
            if (!left && !right) return;
            FoundShop shop = page.state.pageShops().get(slot - 10);
            if (right && (shift || presentation.command(shop, false).isBlank())) {
                inspect(player, page.state, shop);
            } else {
                presentation.navigate(player, shop, left);
                player.closeInventory();
            }
        } else if (slot == 45 || slot == 53) {
            if (slot == 45) page.state.previousPage();
            else page.state.nextPage();
            open(player, page.state);
        } else if (slot == 49) player.closeInventory();
    }

    @EventHandler
    public void drag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof Page || holder instanceof Inspection) event.setCancelled(true);
    }
}
