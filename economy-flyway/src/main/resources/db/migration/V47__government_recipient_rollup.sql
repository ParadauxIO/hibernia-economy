-- Preserve source account and plugin so payroll recipients can be scoped and
-- categorised at read time without scanning months of ledger on a page request.
CREATE TABLE explorer_rollup_gov_recipient_day (
    account_id   INT UNSIGNED NOT NULL,
    day          DATE NOT NULL,
    plugin_key   VARCHAR(64) NOT NULL,
    recipient_id INT UNSIGNED NOT NULL,
    total        DECIMAL(19,2) NOT NULL,
    payments     INT UNSIGNED NOT NULL,
    last_paid    DATETIME(3) NOT NULL,
    PRIMARY KEY (account_id, day, plugin_key, recipient_id),
    KEY idx_ergrd_day (day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
