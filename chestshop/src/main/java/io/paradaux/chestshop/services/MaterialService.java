package io.paradaux.chestshop.services;

import io.paradaux.chestshop.utils.MaterialUtil;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * The config/state-backed material logic split out of {@code MaterialUtil} (PAR-282): item
 * equality that honours the {@code EXCLUDED_ITEM_ATTRIBUTES} config, and the config-sized
 * material-name cache. The pure, stateless helpers stay static on {@link MaterialUtil}; the
 * implementation composes {@link MaterialUtil#resolveMaterial}.
 *
 * @author Acrobot
 */
public interface MaterialService {

    /**
     * Checks if the itemStacks are equal, ignoring their amount (and the configured
     * {@code EXCLUDED_ITEM_ATTRIBUTES}).
     *
     * @param one first itemStack
     * @param two second itemStack
     * @return Are they equal?
     */
    boolean equals(ItemStack one, ItemStack two);

    /**
     * Gives you a Material from a String (doesn't have to be fully typed in), backed by a
     * config-sized cache over {@link MaterialUtil#resolveMaterial(String)}.
     *
     * @param name Name of the material
     * @return Material found
     */
    Material getMaterial(String name);
}
