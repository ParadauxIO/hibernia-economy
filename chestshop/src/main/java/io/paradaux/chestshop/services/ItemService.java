package io.paradaux.chestshop.services;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Resolves the item/sign strings ChestShop puts on signs. Replaces the
 * {@code ItemParseEvent} / {@code ItemStringQueryEvent} / {@code MaterialParseEvent} /
 * {@code SignValidationEvent} carriers and their resolver "listeners" with direct,
 * ordered service methods (PAR-282). The resolution order is unchanged:
 * <ul>
 *   <li>{@link #parse}: custom-item resolver (if registered) → configured alias → vanilla
 *       material (the alias step overrides a custom-item match, as before);</li>
 *   <li>{@link #queryString}: custom-item key (if registered) → vanilla name → alias override;</li>
 *   <li>{@link #parseMaterial}: a single resolver.</li>
 * </ul>
 *
 * <p>Sign-format validation is pure and lives on {@code SignService.validateSign}.</p>
 *
 * <p>The optional custom-item integration (a softdepend such as Nexo) registers a
 * {@link CustomItemResolver} when it hooks — the service never references the integration's
 * classes directly, keeping them off the call path when the plugin is absent (PAR-314).
 */
public interface ItemService {

    /** Register a custom-item resolver (a soft-dependency integration hooks itself in here). */
    void registerCustomItemResolver(CustomItemResolver resolver);

    /** Reload the configurable item aliases (and Nexo's nexo.yml, if hooked) from disk (on {@code /chestshop reload}). */
    void reloadAliases();

    /** Parse a sign item string into an {@link ItemStack} (Nexo / alias / vanilla material), or {@code null}. */
    ItemStack parse(String itemString);

    /** The sign string for an item (Nexo / vanilla name / configured alias), within {@code maxWidth}. */
    String queryString(ItemStack item, int maxWidth);

    /** Resolve the material part of an item code (vanilla material lookup), or {@code null}. */
    Material parseMaterial(String materialString, short data);

    /** A comma-joined "count name" list for a set of stacks (used in trade/give messages). */
    String getItemList(ItemStack[] items);

    /** The item's full (untruncated) ChestShop name/code. */
    String getName(ItemStack itemStack);

    /**
     * The item's ChestShop name/code, constrained to {@code maxWidth} pixels (0 = unlimited).
     * Throws {@link IllegalArgumentException} if a width-shortened code no longer round-trips
     * back to the same item.
     */
    String getName(ItemStack itemStack, int maxWidth);

    /** The item's name as it appears on a sign (constrained to the sign width). */
    String getSignName(ItemStack itemStack);
}
