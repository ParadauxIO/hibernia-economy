-- Shop aliases are case-insensitive shortcuts for existing firm accounts.
ALTER TABLE firm_accounts
    ADD COLUMN shop_alias VARCHAR(12) CHARACTER SET ascii COLLATE ascii_general_ci NULL,
    ADD UNIQUE KEY uq_firm_shop_alias (shop_alias);
