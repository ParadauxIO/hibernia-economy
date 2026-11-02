-- PAR-553: deploy before the plugins that append collection-time bracket tags.
-- Keep old corporate messages classified while accepting the bracket suffix.
ALTER TABLE ledger_txns MODIFY COLUMN tax_kind VARCHAR(9) GENERATED ALWAYS AS (
    CASE
        WHEN plugin_system = 'treasury' AND message LIKE 'Personal balance tax (%' THEN 'personal'
        WHEN plugin_system = 'business' AND (message = 'Weekly Corporate Balance Tax'
            OR message LIKE 'Weekly Corporate Balance Tax [bracket:%') THEN 'corporate'
        WHEN plugin_system = 'treasury' AND message LIKE 'Income tax on deposit from %' THEN 'income'
        WHEN plugin_system = 'ChestShop' AND message LIKE 'ChestShop sales tax (%' THEN 'sales'
    END
) VIRTUAL;

-- An independent job backfills historical receipts without invalidating other
-- dashboards. Empty bracket_key means the collection-time bracket is unavailable.
CREATE TABLE explorer_rollup_tax_bracket_day (
    account_id INT UNSIGNED NOT NULL,
    day DATE NOT NULL,
    tax_kind VARCHAR(9) NOT NULL,
    bracket_key VARCHAR(255) NOT NULL,
    collected DECIMAL(19,2) NOT NULL,
    PRIMARY KEY (account_id, day, tax_kind, bracket_key),
    KEY idx_ertbd_day (day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
