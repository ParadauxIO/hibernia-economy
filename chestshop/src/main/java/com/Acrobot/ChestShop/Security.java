package com.Acrobot.ChestShop;
/** Legacy lock types used by BoltChestShop. */
public final class Security {
    private Security() {}

    public enum Type {
        PRIVATE,
        PUBLIC,
        DONATION,
        DISPLAY
    }
}
