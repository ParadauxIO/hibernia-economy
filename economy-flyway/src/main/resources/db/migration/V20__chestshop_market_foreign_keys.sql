-- Add the missing foreign keys to the ChestShop market index tables (ADT-24).
--
-- V6 created chestshop_sale and chestshop_shop with txn_id / shop_account_id /
-- shop_firm_id columns that reference ledger_txns / accounts / firm only "in
-- spirit" — there were no FK constraints, so a ChestShop writer could insert a
-- sale or shop row pointing at a non-existent txn/account/firm, and the
-- explorer/REST market pages (which JOIN these) would surface broken rows.
--
-- These are an analytics index, not a money store, so the references are
-- nullable and ON DELETE SET NULL: when a referenced ledger txn / account /
-- firm is removed, the market row survives with the stale link cleared rather
-- than the history disappearing.
--
-- MySQL/MariaDB reject ADD CONSTRAINT while orphan rows exist, so each FK is
-- preceded by an orphan-cleanup pass that NULLs any value with no parent — the
-- same end state ON DELETE SET NULL would have produced had the parent been
-- deleted through the constraint. The columns are already indexed
-- (idx_cs_txn/idx_cs_acct/idx_cs_firm, idx_shop_acct/idx_shop_firm), so the FKs
-- reuse those indexes. chestshop_shop has no txn_id, so it gets two FKs.

-- ── chestshop_sale ────────────────────────────────────────────────────────────

UPDATE chestshop_sale s
LEFT JOIN ledger_txns t ON s.txn_id = t.txn_id
SET s.txn_id = NULL
WHERE s.txn_id IS NOT NULL AND t.txn_id IS NULL;

UPDATE chestshop_sale s
LEFT JOIN accounts a ON s.shop_account_id = a.account_id
SET s.shop_account_id = NULL
WHERE s.shop_account_id IS NOT NULL AND a.account_id IS NULL;

UPDATE chestshop_sale s
LEFT JOIN firm f ON s.shop_firm_id = f.firm_id
SET s.shop_firm_id = NULL
WHERE s.shop_firm_id IS NOT NULL AND f.firm_id IS NULL;

ALTER TABLE chestshop_sale
    ADD CONSTRAINT fk_cs_sale_txn
        FOREIGN KEY (txn_id) REFERENCES ledger_txns (txn_id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_cs_sale_account
        FOREIGN KEY (shop_account_id) REFERENCES accounts (account_id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_cs_sale_firm
        FOREIGN KEY (shop_firm_id) REFERENCES firm (firm_id) ON DELETE SET NULL;

-- ── chestshop_shop ────────────────────────────────────────────────────────────

UPDATE chestshop_shop s
LEFT JOIN accounts a ON s.shop_account_id = a.account_id
SET s.shop_account_id = NULL
WHERE s.shop_account_id IS NOT NULL AND a.account_id IS NULL;

UPDATE chestshop_shop s
LEFT JOIN firm f ON s.shop_firm_id = f.firm_id
SET s.shop_firm_id = NULL
WHERE s.shop_firm_id IS NOT NULL AND f.firm_id IS NULL;

ALTER TABLE chestshop_shop
    ADD CONSTRAINT fk_cs_shop_account
        FOREIGN KEY (shop_account_id) REFERENCES accounts (account_id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_cs_shop_firm
        FOREIGN KEY (shop_firm_id) REFERENCES firm (firm_id) ON DELETE SET NULL;
