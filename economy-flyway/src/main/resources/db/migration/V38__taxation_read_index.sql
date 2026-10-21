-- Taxation must not scan every government posting (including all history), or
-- every recent ChestShop sale, to find the small subset of tax transactions.
-- These are the same writer/message pairs used by the public taxation page.
-- Classification is independent of posting sign: only government credits count
-- as taxes; the reader still checks sign and identifies fines from their records.
-- Apply before deploying the updated explorer query. Building this index reads
-- the existing ledger once; subsequent dashboard requests use bounded ranges.
ALTER TABLE ledger_txns
    ADD COLUMN tax_kind VARCHAR(9) GENERATED ALWAYS AS (
        CASE
            WHEN plugin_system = 'treasury' AND message LIKE 'Personal balance tax (%' THEN 'personal'
            WHEN plugin_system = 'business' AND message = 'Weekly Corporate Balance Tax' THEN 'corporate'
            WHEN plugin_system = 'treasury' AND message LIKE 'Income tax on deposit from %' THEN 'income'
            WHEN plugin_system = 'ChestShop' AND message LIKE 'ChestShop sales tax (%' THEN 'sales'
        END
    ) VIRTUAL,
    ADD INDEX idx_ledger_tax_kind_time (tax_kind, settlement_time);
