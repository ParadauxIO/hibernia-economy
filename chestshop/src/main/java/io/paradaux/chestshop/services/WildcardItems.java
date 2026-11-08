package io.paradaux.chestshop.services;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Sign patterns are literal, case-insensitive prefixes with one trailing star, never regexes. */
@Singleton
public final class WildcardItems {
    static final java.util.regex.Pattern PATTERN = java.util.regex.Pattern.compile("[a-zA-Z0-9_:.-]+\\*");
    private final ItemService items;

    @Inject
    public WildcardItems(ItemService items) {
        this.items = items;
    }

    public static boolean isPattern(String code) {
        return code != null && PATTERN.matcher(code).matches();
    }

    private static String prefix(String pattern) {
        return isPattern(pattern) ? pattern.substring(0, pattern.length() - 1).toLowerCase(Locale.ROOT) : null;
    }

    public boolean matches(String pattern, ItemStack item) {
        return matchesPrefix(prefix(pattern), item);
    }

    private boolean matchesPrefix(String prefix, ItemStack item) {
        if (prefix == null || item == null || item.getType().isAir() || item.getAmount() < 1) return false;
        String name = items.wildcardName(item);
        return name != null && name.toLowerCase(Locale.ROOT).startsWith(prefix);
    }

    /** Existence checks need no clones, metadata deduplication or complete scan. */
    public boolean hasMatch(String pattern, Inventory inventory) {
        String prefix = prefix(pattern);
        if (inventory == null || prefix == null) return false;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (matchesPrefix(prefix, stack)) return true;
        }
        return false;
    }

    public int count(String pattern, Inventory inventory) {
        String prefix = prefix(pattern);
        if (inventory == null || prefix == null) return 0;
        int count = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (matchesPrefix(prefix, stack)) count += stack.getAmount();
        }
        return count;
    }

    /** UI representatives only: keep the real instance metadata for preview and purchase. */
    public List<ItemStack> choices(String pattern, Inventory inventory) {
        var seen = new java.util.HashSet<String>();
        return variants(pattern, inventory).stream()
                .filter(item -> {
                    String id = item.getMaxStackSize() == 1 ? items.customItemId(item) : null;
                    return id == null || seen.add(id);
                })
                .toList();
    }

    /** One unmodified sample of each distinct metadata variant, in inventory order. */
    public List<ItemStack> variants(String pattern, Inventory inventory) {
        String prefix = prefix(pattern);
        if (inventory == null || prefix == null) return List.of();
        List<ItemStack> variants = new ArrayList<>();
        for (ItemStack stack : inventory.getStorageContents()) {
            if (matchesPrefix(prefix, stack) && variants.stream().noneMatch(item -> item.isSimilar(stack))) {
                ItemStack sample = stack.clone();
                sample.setAmount(1);
                variants.add(sample);
            }
        }
        return List.copyOf(variants);
    }
}
