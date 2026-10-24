package io.paradaux.chestshop.services;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import io.paradaux.chestshop.model.FoundShop;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Native finder presentation; imports the old add-on settings once, without overwriting edits. */
@Singleton
public final class FindPresentation {
    private YamlConfiguration settings = new YamlConfiguration();
    private final File folder;

    public FindPresentation() {
        folder = null;
    }

    @Inject
    public FindPresentation(@Named("dataFolder") File folder) {
        this.folder = folder;
        reload();
    }

    public void reload() {
        if (folder == null) return;
        File target = new File(folder, "find.yml");
        settings = load(target);
        if (!target.exists()) {
            File legacy = new File(folder.getParentFile(), "ChestShopDatabase/settings.yml");
            settings = load(legacy);
            if (!settings.isSet("shop-preview-scale")) settings.set("shop-preview-scale", 0.5);
            if (!settings.isSet("shop-preview-default-visibility"))
                settings.set("shop-preview-default-visibility", true);
            if (!settings.isSet("shop-icon-left-click-command"))
                settings.set("shop-icon-left-click-command", "compass transit <x> <y> <z>");
            if (!settings.isSet("shop-icon-right-click-command"))
                settings.set("shop-icon-right-click-command", "compass <x> <y> <z>");
            try {
                settings.save(target);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot save finder settings", e);
            }
        }
    }

    public float previewScale() {
        double scale = settings.getDouble("shop-preview-scale", 0.5);
        return Double.isFinite(scale) && scale > 0 && scale <= 4 ? (float) scale : 0.5f;
    }

    public boolean defaultVisible() {
        return settings.getBoolean("shop-preview-default-visibility", true);
    }

    public String command(FoundShop shop, boolean transit) {
        String template = settings.getString(
                transit ? "shop-icon-left-click-command" : "shop-icon-right-click-command",
                transit ? "compass transit <x> <y> <z>" : "compass <x> <y> <z>");
        return template.replace("<x>", Integer.toString(shop.x()))
                .replace("<y>", Integer.toString(shop.y()))
                .replace("<z>", Integer.toString(shop.z()));
    }

    public void navigate(Player player, FoundShop shop, boolean transit) {
        // A configured command runs with the customer's permissions, never as console.
        if (!player.getWorld().getName().equals(shop.world())) return;
        String command = command(shop, transit).strip();
        if (command.startsWith("/")) command = command.substring(1);
        if (!command.isEmpty() && command.indexOf('\n') < 0 && command.indexOf('\r') < 0)
            player.performCommand(command);
    }

    private String prefix(FoundShop shop) {
        return "shop-template-" + shop.shopType().name().toLowerCase(java.util.Locale.ROOT);
    }

    public ItemStack icon(FoundShop shop) {
        Material material = Material.matchMaterial(settings.getString(prefix(shop) + ".material", ""));
        return material != null && material.isItem() && !material.isAir() ? new ItemStack(material) : null;
    }

    public Component title(FoundShop shop, ShopDisplayService displays) {
        return render(settings.getString(prefix(shop) + ".display-name", "<aqua>%owner%</aqua>"), shop, displays);
    }

    public List<Component> lore(FoundShop shop, ShopDisplayService displays) {
        List<String> lines = settings.getStringList(prefix(shop) + ".lore");
        if (lines.isEmpty())
            lines = List.of(
                    "Buy: %buy-price% (%buy-price-unit%/ea) | Sell: %sell-price% (%sell-price-unit%/ea)",
                    "Quantity: %quantity% | Stock: %stock% | Remaining capacity: %capacity%", "%world%: %x%, %y%, %z%");
        return lines.stream().map(line -> render(line, shop, displays)).toList();
    }

    public Component details(FoundShop shop, ShopDisplayService displays) {
        String path = prefix(shop);
        String name = settings.getString(path + ".display-name", "<aqua>%owner%</aqua>");
        List<String> lore = settings.getStringList(path + ".lore");
        if (lore.isEmpty())
            lore = List.of(
                    "Buy: %buy-price% (%buy-price-unit%/ea) | Sell: %sell-price% (%sell-price-unit%/ea)",
                    "Quantity: %quantity% | Stock: %stock% | Remaining capacity: %capacity%", "%world%: %x%, %y%, %z%");
        String template = name + "\n" + String.join("\n", lore);
        return render(template, shop, displays);
    }

    private Component render(String template, FoundShop shop, ShopDisplayService displays) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("owner", shop.ownerName() == null ? "Admin Shop" : shop.ownerName());
        values.put("quantity", Integer.toString(shop.batchQty()));
        values.put("stock", shop.stock() == null ? "∞" : shop.stock().toString());
        values.put("capacity", shop.capacity() == null ? "∞" : shop.capacity().toString());
        values.put("buy-price", price(shop.buyPriceValue()));
        values.put("sell-price", price(shop.sellPriceValue()));
        values.put("buy-price-unit", price(shop.unitBuyPrice()));
        values.put("sell-price-unit", price(shop.unitSellPrice()));
        values.put("distance", shop.distanceBlocks() < 0 ? "∞" : shop.distanceBlocks() + "m");
        values.put("world", shop.world());
        values.put("x", Integer.toString(shop.x()));
        values.put("y", Integer.toString(shop.y()));
        values.put("z", Integer.toString(shop.z()));
        values.put("region-name", displays.format("%region-name%", shop.world(), shop.x(), shop.y(), shop.z()));
        MiniMessage mini = MiniMessage.miniMessage();
        for (var entry : values.entrySet())
            template = template.replace(
                    "%" + entry.getKey() + "%", mini.escapeTags(entry.getValue() == null ? "" : entry.getValue()));
        return mini.deserialize(template);
    }

    private static String price(Double value) {
        return value == null ? "—" : String.format(java.util.Locale.ROOT, "$%.2f", value);
    }

    private static YamlConfiguration load(File file) {
        YamlConfiguration config = new YamlConfiguration();
        if (!file.exists()) return config;
        try {
            config.load(file);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IllegalStateException("Cannot read finder settings; original file preserved", e);
        }
        return config;
    }
}
