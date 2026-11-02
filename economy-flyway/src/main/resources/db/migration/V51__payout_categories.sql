-- PAR-554: old transactions stay uncategorized; do not guess from free-form memos.
ALTER TABLE ledger_txns
    ADD COLUMN payout_category VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD CONSTRAINT chk_ledger_payout_category
        CHECK (payout_category IS NULL OR BINARY payout_category IN ('EMPLOYEE', 'AUCTION_PROCEEDS'));
