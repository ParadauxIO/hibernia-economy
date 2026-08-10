package io.paradaux.chestshop.services.impl;
import io.paradaux.chestshop.utils.Colours;
import lombok.extern.slf4j.Slf4j;

import io.paradaux.chestshop.services.ShopBlockService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.model.ItemInfoLines;
import io.paradaux.chestshop.services.InventoryService;
import io.paradaux.chestshop.services.EconomyService;
import io.paradaux.chestshop.services.SignService;
import io.paradaux.chestshop.services.AccountService;
import io.paradaux.chestshop.services.InfoService;
import io.paradaux.chestshop.utils.MessageUtil;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.ChestShop;
import io.paradaux.chestshop.model.config.ChestShopConfiguration;
import io.paradaux.hibernia.framework.i18n.Message;
import io.paradaux.chestshop.model.Account;
import io.paradaux.chestshop.utils.PriceUtil;
import io.paradaux.chestshop.utils.StringUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.AxolotlBucketMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.KnowledgeBookMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.meta.TropicalFishBucketMeta;
import org.bukkit.map.MapView;
import org.bukkit.potion.PotionEffect;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.paradaux.chestshop.utils.NumberUtil.toRoman;
import static io.paradaux.chestshop.utils.NumberUtil.toTime;
import static io.paradaux.chestshop.utils.StringUtil.capitalizeFirstLetter;

/**
 * Owns the read-only {@code /shopinfo} and {@code /iteminfo} output. Replaces the old
 * {@code ShopInfoEvent} / {@code ItemInfoEvent} carriers and the three contributor
 * "listener" classes (ShopInfoListener, ItemInfoListener, ExtendedItemInfoListener)
 * with direct, ordered service methods — the entrypoint→service shape the rest of the
 * monorepo uses (PAR-282).
 *
 * <p>{@link #collectItemInfo} runs the contributors in their exact former firing order:
 * the basic info lines first, then the richer type-specific lines; the {@code map}/
 * {@code potion} "extended" lines intentionally override the basic versions by reusing
 * the same message key (the accumulating {@link ItemInfoLines} keeps the original line
 * position).
 *
 * @author Acrobot
 */
@Singleton
@Slf4j
public class InfoServiceImpl implements InfoService {

    private final AccountService accounts;
    private final EconomyService economy;
    private final ItemService items;
    private final Message message;
    private final ChestShopConfiguration config;
    private final SignService signService;
    private final ShopBlockService shopBlockService;
    private final InventoryService inventoryService;

    @Inject
    public InfoServiceImpl(AccountService accounts, EconomyService economy, ItemService items, Message message,
                       ChestShopConfiguration config, SignService signService, ShopBlockService shopBlockService,
                       InventoryService inventoryService) {
        this.accounts = accounts;
        this.economy = economy;
        this.items = items;
        this.message = message;
        this.config = config;
        this.signService = signService;
        this.shopBlockService = shopBlockService;
        this.inventoryService = inventoryService;
    }

    // ---- /shopinfo -------------------------------------------------------------

    /** Render the {@code /shopinfo} (or middle-click) output for a shop sign. */
    @Override
    public void showShopInfo(Player sender, Sign sign) {
        if (!signService.isValid(sign)) {
            message.send(sender, "chestshop.INVALID_SHOP_DETECTED");
            return;
        }

        String nameLine = SignService.getOwner(sign);
        int amount;
        try {
            amount = SignService.getQuantity(sign);
        } catch (NumberFormatException notANumber) {
            message.send(sender, "chestshop.INVALID_SHOP_DETECTED");
            return;
        }
        String pricesLine = SignService.getPrice(sign);

        Account account = accounts.resolveAccount(nameLine);
        if (account == null) {
            message.send(sender, "chestshop.INVALID_SHOP_DETECTED");
            return;
        }

        String ownerName = account.getName();
        ownerName = ownerName != null ? ownerName : nameLine;

        ItemStack item = items.parse(SignService.getItem(sign));
        if (item == null || amount < 1) {
            message.send(sender, "chestshop.INVALID_SHOP_DETECTED");
            return;
        }

        Container shopBlock = shopBlockService.findConnectedContainer(sign);
        String stock = shopBlock != null
                ? String.valueOf(inventoryService.getAmount(item, shopBlock.getInventory()))
                : "∞"; // Infinity symbol

        Map<String, String> replacementMap = ImmutableMap.of(
                "item", items.getName(item),
                "stock", stock,
                "owner", ownerName,
                "prices", pricesLine,
                "quantity", String.valueOf(amount)
        );
        sender.sendMessage(message.component("chestshop.shopinfo", MessageUtil.values(false, replacementMap)));

        BigDecimal buyPrice = PriceUtil.getExactBuyPrice(pricesLine);
        BigDecimal sellPrice = PriceUtil.getExactSellPrice(pricesLine);

        ItemInfoLines lines = collectItemInfo(sender, item);
        for (Map.Entry<String, Component> entry : lines.getMessages()) {
            sender.sendMessage(entry.getValue());
        }

        if (!buyPrice.equals(PriceUtil.NO_PRICE)) {
            message.send(sender, "chestshop.shopinfo_buy", "prefix", "",
                    "amount", String.valueOf(amount),
                    "price", economy.format(buyPrice)
            );
        }
        if (!sellPrice.equals(PriceUtil.NO_PRICE)) {
            message.send(sender, "chestshop.shopinfo_sell", "prefix", "",
                    "amount", String.valueOf(amount),
                    "price", economy.format(sellPrice)
            );
        }
    }

    // ---- /iteminfo -------------------------------------------------------------

    /** Accumulate the {@code /iteminfo} lines for an item, in the former contributor order. */
    @Override
    public ItemInfoLines collectItemInfo(CommandSender sender, ItemStack item) {
        ItemInfoLines lines = new ItemInfoLines(sender, item, message);
        // Basic info lines (were ItemInfoListener @NORMAL), in declaration order.
        addRepairCost(lines);
        addEnchantment(lines);
        addLeatherColor(lines);
        addRecipes(lines);
        addTropicalFishInfo(lines);
        addBasicMapInfo(lines);
        addBasicPotionInfo(lines);
        addBookInfo(lines);
        addLoreInfo(lines);
        // Type-specific lines (were ExtendedItemInfoListener), then the map/potion
        // handlers that override the basic versions above (same keys).
        addCrossBowInfo(lines);
        addAxolotlInfo(lines);
        addBundleInfo(lines);
        addArmorInfo(lines);
        addExtendedMapInfo(lines);
        addExtendedPotionInfo(lines);
        return lines;
    }

    @Override
    public boolean sendItemName(CommandSender sender, ItemStack item, String messageKey) {
        try {
            Map<String, String> replacementMap = ImmutableMap.of("item", items.getName(item));
            sender.sendMessage(message.component(messageKey, MessageUtil.values(false, replacementMap)));
        } catch (IllegalArgumentException e) {
            message.send(sender, "chestshop.FULL_NAME_ERROR");
            log.error("Error while generating full item name", e);
            return false;
        }
        return true;
    }

    // ---- basic item-info contributors (were ItemInfoListener) ------------------

    private void addRepairCost(ItemInfoLines lines) {
        if (lines.getItem().hasItemMeta()) {
            ItemMeta meta = lines.getItem().getItemMeta();
            if (meta instanceof Repairable && ((Repairable) meta).getRepairCost() > 0) {
                lines.addMessage("chestshop.iteminfo_repaircost", "cost", String.valueOf(((Repairable) meta).getRepairCost()));
            }
        }
    }

    private void addEnchantment(ItemInfoLines lines) {
        ItemStack item = lines.getItem();
        List<String> enchantLines = new ArrayList<>();

        for (Map.Entry<Enchantment, Integer> enchantment : item.getEnchantments().entrySet()) {
            enchantLines.add(Colours.AQUA + capitalizeFirstLetter(enchantment.getKey().getName(), '_') + ' ' + toRoman(enchantment.getValue()));
        }

        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof EnchantmentStorageMeta) {
                for (Map.Entry<Enchantment, Integer> enchantment : ((EnchantmentStorageMeta) meta).getStoredEnchants().entrySet()) {
                    enchantLines.add(Colours.YELLOW + capitalizeFirstLetter(enchantment.getKey().getName(), '_') + ' ' + toRoman(enchantment.getValue()));
                }
            }
        }

        lines.addRawMessage("iteminfo_enchantments", String.join("\n", enchantLines));
    }

    private void addLeatherColor(ItemInfoLines lines) {
        if (lines.getItem().hasItemMeta()) {
            ItemMeta meta = lines.getItem().getItemMeta();
            if (meta instanceof LeatherArmorMeta) {
                Color color = ((LeatherArmorMeta) meta).getColor();
                lines.addMessage("chestshop.iteminfo_leather_color",
                        "colorred", String.valueOf(color.getRed()),
                        "colorgreen", String.valueOf(color.getGreen()),
                        "colorblue", String.valueOf(color.getBlue()),
                        "colorhex", Integer.toHexString(color.asRGB())
                );
            }
        }
    }

    private void addRecipes(ItemInfoLines lines) {
        if (lines.getItem().hasItemMeta()) {
            ItemMeta meta = lines.getItem().getItemMeta();
            if (meta instanceof KnowledgeBookMeta && !((KnowledgeBookMeta) meta).getRecipes().isEmpty()) {
                lines.addMessage("chestshop.iteminfo_recipes");
                for (NamespacedKey recipe : ((KnowledgeBookMeta) meta).getRecipes()) {
                    lines.getSender().sendMessage(Colours.GRAY + recipe.toString());
                }
            }
        }
    }

    private void addTropicalFishInfo(ItemInfoLines lines) {
        if (lines.getItem().hasItemMeta()) {
            ItemMeta meta = lines.getItem().getItemMeta();
            if (meta instanceof TropicalFishBucketMeta && ((TropicalFishBucketMeta) meta).hasVariant()) {
                lines.addMessage("chestshop.iteminfo_tropical_fish",
                        "pattern", capitalizeFirstLetter(((TropicalFishBucketMeta) meta).getPattern().name()),
                        "patterncolor", capitalizeFirstLetter(((TropicalFishBucketMeta) meta).getPatternColor().name()),
                        "bodycolor", capitalizeFirstLetter(((TropicalFishBucketMeta) meta).getBodyColor().name())
                );
            }
        }
    }

    private void addBasicMapInfo(ItemInfoLines lines) {
        if (lines.getItem().hasItemMeta()) {
            ItemMeta meta = lines.getItem().getItemMeta();
            if (meta instanceof MapMeta) {
                if (((MapMeta) meta).getMapView() != null) {
                    MapView mapView = ((MapMeta) meta).getMapView();
                    lines.addMessage("chestshop.iteminfo_map_view",
                            "id", String.valueOf(mapView.getId()),
                            "x", String.valueOf(mapView.getCenterX()),
                            "z", String.valueOf(mapView.getCenterZ()),
                            "world", mapView.getWorld() != null ? mapView.getWorld().getName() : "unknown",
                            "scale", capitalizeFirstLetter(mapView.getScale().name(), '_'),
                            "locked", "false"
                    );
                }
                if (((MapMeta) meta).hasLocationName()) {
                    lines.addMessage("chestshop.iteminfo_map_location", "location", String.valueOf(((MapMeta) meta).getLocationName()));
                }
            }
        }
    }

    private void addBasicPotionInfo(ItemInfoLines lines) {
        ItemStack item = lines.getItem();
        if (!item.hasItemMeta()) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof PotionMeta)) {
            return;
        }

        PotionMeta potionMeta = (PotionMeta) meta;

        StringBuilder message = new StringBuilder();
        message.append(Colours.GRAY);
        message.append(capitalizeFirstLetter(item.getType().name(), '_')).append(" of ");
        message.append(capitalizeFirstLetter(potionMeta.getBasePotionData().getType().name(), '_')).append(' ');
        if (potionMeta.getBasePotionData().isUpgraded()) {
            message.append("II");
        } else if (potionMeta.getBasePotionData().isExtended()) {
            message.append("+");
        }

        for (PotionEffect effect : potionMeta.getCustomEffects()) {
            message.append("\n" + Colours.DARK_GRAY + capitalizeFirstLetter(effect.getType().getName(), '_') + ' ' + toTime(effect.getDuration() / 20));
        }
        lines.addRawMessage("iteminfo_potion", message.toString());
    }

    private void addBookInfo(ItemInfoLines lines) {
        if (!lines.getItem().hasItemMeta()) {
            return;
        }
        ItemMeta meta = lines.getItem().getItemMeta();
        if (meta instanceof BookMeta) {
            BookMeta book = (BookMeta) meta;
            lines.addMessage("chestshop.iteminfo_book",
                    "title", book.getTitle(),
                    "author", book.getAuthor(),
                    "pages", String.valueOf(book.getPageCount())
            );
            if (book.hasGeneration()) {
                lines.addMessage("chestshop.iteminfo_book_generation",
                        "generation", StringUtil.capitalizeFirstLetter(book.getGeneration().name(), '_')
                );
            }
        }
    }

    private void addLoreInfo(ItemInfoLines lines) {
        if (lines.getItem().hasItemMeta()) {
            ItemMeta meta = lines.getItem().getItemMeta();
            if (meta.hasLore()) {
                lines.addMessage("chestshop.iteminfo_lore", "lore", String.join("\n", meta.getLore()));
            }
        }
    }

    // ---- extended type-specific contributors (were ExtendedItemInfoListener) ----

    private void addCrossBowInfo(ItemInfoLines lines) {
        if (lines.getItem().hasItemMeta()) {
            ItemMeta meta = lines.getItem().getItemMeta();
            if (meta instanceof CrossbowMeta && ((CrossbowMeta) meta).hasChargedProjectiles()) {
                lines.addMessage("chestshop.iteminfo_crossbow_projectiles");
                for (ItemStack chargedProjectile : ((CrossbowMeta) meta).getChargedProjectiles()) {
                    sendItemName(lines.getSender(), chargedProjectile, "chestshop.iteminfo_crossbow_projectile");
                    ItemInfoLines projectile = collectItemInfo(lines.getSender(), chargedProjectile);
                    for (Map.Entry<String, Component> entry : projectile.getMessages()) {
                        lines.addRawMessage("crossbow_projectile_" + chargedProjectile.hashCode() + "_" + entry.getKey(), entry.getValue());
                    }
                    lines.addRawMessage("crossbow_projectile_" + chargedProjectile.hashCode() + "_divider", Colours.GRAY + "---");
                }
            }
        }
    }

    private void addAxolotlInfo(ItemInfoLines lines) {
        if (lines.getItem().hasItemMeta()) {
            ItemMeta meta = lines.getItem().getItemMeta();
            if (meta instanceof AxolotlBucketMeta) {
                lines.addMessage("chestshop.iteminfo_axolotl_variant", "variant", capitalizeFirstLetter(((AxolotlBucketMeta) meta).getVariant().name(), '_'));
            }
        }
    }

    private void addBundleInfo(ItemInfoLines lines) {
        if (lines.getItem().hasItemMeta()) {
            ItemMeta meta = lines.getItem().getItemMeta();
            if (meta instanceof BundleMeta) {
                lines.addMessage("chestshop.iteminfo_bundle_items", "itemcount", String.valueOf(((BundleMeta) meta).getItems().size()));
            }
        }
    }

    private void addArmorInfo(ItemInfoLines lines) {
        if (lines.getItem().hasItemMeta()) {
            ItemMeta meta = lines.getItem().getItemMeta();
            if (meta instanceof ArmorMeta && ((ArmorMeta) meta).hasTrim()) {
                lines.addMessage("chestshop.iteminfo_armor_trim",
                        "pattern", capitalizeFirstLetter(((ArmorMeta) meta).getTrim().getPattern().getKey().getKey(), '_'),
                        "material", capitalizeFirstLetter(((ArmorMeta) meta).getTrim().getMaterial().getKey().getKey(), '_'));
            }
        }
    }

    private void addExtendedMapInfo(ItemInfoLines lines) {
        if (lines.getItem().hasItemMeta()) {
            ItemMeta meta = lines.getItem().getItemMeta();
            if (meta instanceof MapMeta) {
                if (((MapMeta) meta).getMapView() != null) {
                    MapView mapView = ((MapMeta) meta).getMapView();
                    lines.addMessage("chestshop.iteminfo_map_view",
                            "id", String.valueOf(mapView.getId()),
                            "x", String.valueOf(mapView.getCenterX()),
                            "z", String.valueOf(mapView.getCenterZ()),
                            "world", mapView.getWorld() != null ? mapView.getWorld().getName() : "unknown",
                            "scale", capitalizeFirstLetter(mapView.getScale().name(), '_'),
                            "locked", String.valueOf(mapView.isLocked())
                    );
                }
                if (((MapMeta) meta).hasLocationName()) {
                    lines.addMessage("chestshop.iteminfo_map_location", "location", String.valueOf(((MapMeta) meta).getLocationName()));
                }
            }
        }
    }

    private void addExtendedPotionInfo(ItemInfoLines lines) {
        ItemStack item = lines.getItem();
        if (!item.hasItemMeta()) {
            return;
        }

        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof PotionMeta)) {
            return;
        }

        PotionMeta potionMeta = (PotionMeta) meta;

        StringBuilder message = new StringBuilder();
        if (potionMeta.getBasePotionType() != null) {
            message.append(Colours.GRAY);
            message.append(capitalizeFirstLetter(item.getType().name(), '_')).append(" of ");
            message.append(capitalizeFirstLetter(potionMeta.getBasePotionType().getKey().getKey(), '_')).append(' ');
        }

        for (PotionEffect effect : potionMeta.getCustomEffects()) {
            if (message.length() > 0) {
                message.append('\n');
            }
            message.append(Colours.DARK_GRAY + capitalizeFirstLetter(effect.getType().getKey().getKey(), '_')
                    + ' ' + (effect.getAmplifier() + 1) + ' ' + toTime(effect.getDuration() / 20));
        }
        if (message.length() > 0) {
            lines.addRawMessage("iteminfo_potion", message.toString());
        }
    }
}
