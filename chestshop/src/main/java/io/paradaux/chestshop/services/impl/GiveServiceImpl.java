package io.paradaux.chestshop.services.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.paradaux.chestshop.services.GiveService;
import io.paradaux.chestshop.services.ItemService;
import io.paradaux.chestshop.utils.MaterialUtil;
import org.bukkit.inventory.ItemStack;

/**
 * The give-item core (PAR-323): parse a ChestShop item code and stamp the requested
 * quantity onto it. Delegates code resolution to {@link ItemService#parse} (Nexo / alias /
 * vanilla material, in that order) and treats an empty/unparseable result as "no such item".
 */
@Singleton
public class GiveServiceImpl implements GiveService {

    private final ItemService items;

    @Inject
    public GiveServiceImpl(ItemService items) {
        this.items = items;
    }

    @Override
    public ItemStack resolveGift(String itemCode, int quantity) {
        ItemStack item = items.parse(itemCode);

        if (MaterialUtil.isEmpty(item)) {
            return null;
        }

        item.setAmount(quantity);
        return item;
    }
}
