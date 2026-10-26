-- One fine per debit: UNIQUE(txn_id) on government_fines.
--
-- GovServiceImpl.doIssueFine debits the debtor through the ledger with a
-- per-second dedup key. A double-submitted `/fine issue` inside that second
-- collapsed onto ONE ledger txn (the ledger dedup did its job) but still inserted
-- a second government_fines row, so two fines pointed at a single debit. The
-- revoke dedup key is per fine_id, so each row could be revoked — refunding the
-- debtor twice for money taken once. The service now returns the existing fine
-- when the debit was deduplicated; this constraint is the database backstop.
--
-- Step 1 makes the constraint addable if such duplicates already exist: per
-- txn_id keep exactly one row and delete the rest. The kept row is the lowest
-- revoked fine_id if any of the group was revoked (its debit has already been
-- refunded, so the surviving row must not be revocable again), otherwise the
-- lowest fine_id. The removed rows never had a debit of their own; any refund
-- already paid against one stays in ledger_txns as "Fine revoked: ...". A no-op
-- when there are no duplicates.
DELETE gf
  FROM government_fines gf
  JOIN (
        SELECT txn_id,
               COALESCE(MIN(CASE WHEN revoked = 1 THEN fine_id END), MIN(fine_id)) AS keep_fine_id
          FROM government_fines
         GROUP BY txn_id
        HAVING COUNT(*) > 1
       ) dup ON dup.txn_id = gf.txn_id
 WHERE gf.fine_id <> dup.keep_fine_id;

-- Step 2: txn_id had only the index InnoDB created implicitly for fk_fines_txn;
-- the UNIQUE key serves that foreign key as well.
ALTER TABLE government_fines
    ADD UNIQUE KEY uq_fines_txn (txn_id);
