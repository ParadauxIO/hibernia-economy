-- Per-transaction settlement time and volume for the explorer's time-window
-- aggregates (PAR-407). The dashboard's daily volume and period comparison joined
-- ledger_txns to ledger_postings over 30–60 days (millions of rows), which
-- outgrew the explorer's statement timeout. These read one narrow covering index
-- instead. volume is the sum of the transaction's positive postings, the same
-- figure the explorer previously derived with the join.
--
-- Maintained by triggers keyed on the transaction's own txn_id, so a writer only
-- ever locks the row its own transaction inserted. A shared per-hour rollup row
-- would serialise every transfer and, taken after LedgerServiceImpl's ascending
-- FOR UPDATE balance locks, could deadlock against them.
CREATE TABLE ledger_txn_volume (
    txn_id          BIGINT UNSIGNED NOT NULL,
    settlement_time TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    volume          DECIMAL(19,2)   NOT NULL DEFAULT 0.00,
    PRIMARY KEY (txn_id),
    KEY idx_ltv_settle_volume (settlement_time, volume),
    CONSTRAINT fk_ltv_txn FOREIGN KEY (txn_id)
        REFERENCES ledger_txns(txn_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Triggers before the backfill: CREATE TRIGGER waits for open transactions on the
-- table, so every transaction is either fully in the backfill's snapshot or seen
-- by the triggers. A transaction seen by both keeps the trigger's (equal) row.
CREATE TRIGGER trg_txns_volume_ai
    AFTER INSERT ON ledger_txns
    FOR EACH ROW
BEGIN
    INSERT INTO ledger_txn_volume (txn_id, settlement_time)
    VALUES (NEW.txn_id, NEW.settlement_time);
END;

CREATE TRIGGER trg_txns_volume_au
    AFTER UPDATE ON ledger_txns
    FOR EACH ROW
BEGIN
    IF NEW.settlement_time <> OLD.settlement_time THEN
        UPDATE ledger_txn_volume
        SET settlement_time = NEW.settlement_time
        WHERE txn_id = NEW.txn_id;
    END IF;
END;

CREATE TRIGGER trg_postings_volume_ai
    AFTER INSERT ON ledger_postings
    FOR EACH ROW
BEGIN
    IF NEW.amount > 0 THEN
        UPDATE ledger_txn_volume
        SET volume = volume + NEW.amount
        WHERE txn_id = NEW.txn_id;
    END IF;
END;

CREATE TRIGGER trg_postings_volume_au
    AFTER UPDATE ON ledger_postings
    FOR EACH ROW
BEGIN
    IF OLD.amount > 0 THEN
        UPDATE ledger_txn_volume
        SET volume = volume - OLD.amount
        WHERE txn_id = OLD.txn_id;
    END IF;
    IF NEW.amount > 0 THEN
        UPDATE ledger_txn_volume
        SET volume = volume + NEW.amount
        WHERE txn_id = NEW.txn_id;
    END IF;
END;

CREATE TRIGGER trg_postings_volume_ad
    AFTER DELETE ON ledger_postings
    FOR EACH ROW
BEGIN
    IF OLD.amount > 0 THEN
        UPDATE ledger_txn_volume
        SET volume = volume - OLD.amount
        WHERE txn_id = OLD.txn_id;
    END IF;
END;

-- One pass over the ledger. It holds shared locks on ledger_txns while it runs,
-- so run it with writers stopped, as the release runbook already requires.
INSERT INTO ledger_txn_volume (txn_id, settlement_time, volume)
SELECT lt.txn_id, lt.settlement_time,
       COALESCE(SUM(CASE WHEN lp.amount > 0 THEN lp.amount ELSE 0 END), 0.00)
FROM ledger_txns lt
LEFT JOIN ledger_postings lp ON lp.txn_id = lt.txn_id
GROUP BY lt.txn_id, lt.settlement_time
ON DUPLICATE KEY UPDATE volume = ledger_txn_volume.volume;
