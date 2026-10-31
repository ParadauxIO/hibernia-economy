package io.paradaux.chestshop.model;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.inventory.ItemStack;

/** Main-thread preview cache. Content keys prevent stale metadata; callers receive independent stacks. */
public final class ShopItemCache {
    private record Key(String material, String data) {}

    private static final int MAX_ENTRIES = 256;
    private static final int MAX_DATA_CHARS = 2 * 1024 * 1024;
    private static final int MAX_ENTRY_CHARS = 64 * 1024;
    private final Map<Key, ItemStack> items = new LinkedHashMap<>(16, 0.75f, true);
    private int dataChars;

    public ItemStack decode(String material, String data) {
        // Plain materials are cheap; very large custom items should not occupy the cache.
        if (data == null || data.length() > MAX_ENTRY_CHARS) return ShopItem.decode(material, data);
        Key key = new Key(material, data);
        ItemStack cached = items.get(key);
        if (cached != null) return cached.clone();
        ItemStack decoded = ShopItem.decode(material, data);
        if (decoded == null) return null;
        items.put(key, decoded.clone());
        dataChars += data.length();
        while (items.size() > MAX_ENTRIES || dataChars > MAX_DATA_CHARS) {
            var eldest = items.entrySet().iterator();
            dataChars -= eldest.next().getKey().data().length();
            eldest.remove();
        }
        return decoded;
    }

    public void clear() {
        items.clear();
        dataChars = 0;
    }
}
