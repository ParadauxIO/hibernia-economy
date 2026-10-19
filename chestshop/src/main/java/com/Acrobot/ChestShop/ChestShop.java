package com.Acrobot.ChestShop;
/** Legacy facade: all item persistence remains owned by the native service. */
public final class ChestShop {
    private ChestShop() {}

    public static com.Acrobot.ChestShop.Metadata.ItemDatabase getItemDatabase() {
        return new com.Acrobot.ChestShop.Metadata.ItemDatabase();
    }
}
