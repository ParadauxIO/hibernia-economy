package io.paradaux.chestshop.services;

import org.bukkit.inventory.ItemStack;

/**
 * Owns the item-code <em>logic</em>: serialising an {@link ItemStack} to/from the
 * short Base62 code shops put on signs, the find-or-create that keeps the store
 * deduplicated, and the one-time re-serialisation when the server's item data
 * version changes. All persistence is delegated to the {@code ItemCodeMapper}.
 *
 * <p>This is the service half of the {@code ItemDatabase} split: the old class
 * mixed YAML/Base64 encoding, find-or-create business rules, and raw SQLite access in
 * one place; that DB access now lives in the MyBatis mapper, leaving this a pure,
 * testable service.
 */
public interface ItemCodeService {

    /** Runs the one-time metadata re-serialisation if the server's data version advanced. */
    void migrateIfNeeded();

    /** The short Base62 code for an item, creating a store row for it if new. */
    String getItemCode(ItemStack item);

    /** The {@link ItemStack} a code refers to, or {@code null} if unknown/corrupt. */
    ItemStack getFromCode(String code);

    /** The canonical sign string for {@code item} (material + durability + #code), within {@code maxWidth} (0 = unlimited). */
    String encode(ItemStack item, int maxWidth);

    /** The {@link ItemStack} for a canonical sign string (material + durability + #code), or {@code null}. */
    ItemStack decode(String itemName);
}
