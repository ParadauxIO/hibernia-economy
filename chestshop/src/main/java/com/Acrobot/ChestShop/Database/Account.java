package com.Acrobot.ChestShop.Database;

import java.util.UUID;

/** Detached account value for the legacy event ABI; never opens an upstream database. */
public class Account extends io.paradaux.chestshop.model.Account {
    public Account() {}

    public Account(String name, UUID uuid) {
        super(name, uuid);
    }

    public Account(String name, String shortName, UUID uuid) {
        super(name, shortName, uuid);
    }

    public boolean isIgnoringMessages() {
        return isIgnoreMessages();
    }
}
