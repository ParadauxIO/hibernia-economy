-- Shop aliases are case-insensitive sign names for existing firm accounts.
ALTER TABLE firm_accounts
    ADD COLUMN shop_alias VARCHAR(12) CHARACTER SET ascii COLLATE ascii_general_ci NULL,
    ADD UNIQUE KEY uq_firm_shop_alias (shop_alias);

-- Retain ownership after changing/clearing an alias so old signs cannot be hijacked.
CREATE TABLE firm_shop_alias_reservations (
    alias VARCHAR(12) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL,
    account_id INT UNSIGNED NOT NULL,
    PRIMARY KEY (alias),
    CONSTRAINT fk_shop_alias_account FOREIGN KEY (account_id)
        REFERENCES firm_accounts(account_id) ON DELETE RESTRICT
) ENGINE=InnoDB;
