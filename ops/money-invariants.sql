-- =============================================================================
-- money-invariants.sql — point-in-time integrity check for the economy ledger.
--
-- Returns exactly ONE row with a `status` column of 'OK' or 'FAIL', plus the
-- individual counters. Intended to be run on a schedule (per tenant) and to
-- alert when status <> 'OK'. Tables are UNQUALIFIED so it runs against whatever
-- database the connection has selected — point the cron at each tenant's DB
-- (e.g. `mysql --database=<tenant_db> < money-invariants.sql`).
--
-- Checks (all must be zero / balanced for 'OK'):
--   global_sum               Σ of every posting. Double-entry => must be 0.00.
--                            Any non-zero means money was created or destroyed.
--   unbalanced_txns          transactions whose postings don't sum to 0 (a
--                            single non-atomic / half-written transfer).
--   mat_mismatches           accounts where the trigger-maintained
--                            account_balances_mat.balance disagrees with the
--                            derived Σ(postings) — i.e. a trigger drift.
--   orphan_posting_accounts  accounts that have postings but no mat row.
--   illegal_negative_accounts non-faucet accounts in the red beyond what they're
--                            allowed: balance < 0 AND not the faucet sentinel
--                            (credit_limit = -1) AND (no overdraft, or past the
--                            credit limit). GOVERNMENT/SYSTEM faucets are
--                            legitimately negative (they issue money) and are
--                            excluded.
--
-- PERFORMANCE: this full-scans + groups ledger_postings (millions of rows), so
-- it's a heavy read. Run it daily / off-peak, or against a replica/backup
-- restore — NOT every minute. At ~5.4M postings it's seconds-to-low-minutes.
--
-- Baseline (verified 2026-06-23 against the live tenant): status OK,
-- global_sum 0.00, all counters 0, ~5.36M postings / 117k accounts. The three
-- negative balances in prod are all GOVERNMENT faucets (credit_limit = -1) and
-- are expected — their negative sum mirrors all positive balances, hence
-- global_sum = 0.
-- =============================================================================

SELECT
  CASE WHEN COALESCE(global_sum,0) = 0
        AND unbalanced_txns          = 0
        AND mat_mismatches           = 0
        AND orphan_posting_accounts  = 0
        AND illegal_negative_accounts = 0
        AND mat_balance_without_trigger = 0
       THEN 'OK' ELSE 'FAIL' END AS status,
  COALESCE(global_sum,0) AS global_sum,
  unbalanced_txns,
  mat_mismatches,
  orphan_posting_accounts,
  illegal_negative_accounts,
  mat_balance_without_trigger,
  postings,
  accounts
FROM (
  SELECT
    (SELECT SUM(amount) FROM ledger_postings) AS global_sum,

    (SELECT COUNT(*) FROM (
        SELECT txn_id FROM ledger_postings GROUP BY txn_id HAVING SUM(amount) <> 0
     ) t) AS unbalanced_txns,

    (SELECT COUNT(*) FROM account_balances_mat m
        LEFT JOIN (SELECT account_id, SUM(amount) s FROM ledger_postings GROUP BY account_id) p
          ON p.account_id = m.account_id
        WHERE m.balance <> COALESCE(p.s,0)) AS mat_mismatches,

    (SELECT COUNT(*) FROM (SELECT DISTINCT account_id FROM ledger_postings) lp
        LEFT JOIN account_balances_mat m ON m.account_id = lp.account_id
        WHERE m.account_id IS NULL) AS orphan_posting_accounts,

    -- Faucets (GOVERNMENT/SYSTEM) are negative by design; identify them by
    -- account_type, not by the overloaded credit_limit = -1 sentinel
    -- (ADT invariants-faucet-sentinel-magic-value). Keying on the sentinel both
    -- false-FAILed a faucet whose credit_limit wasn't exactly -1 and, worse,
    -- false-PASSed a player/firm account accidentally set to -1.
    (SELECT COUNT(*) FROM accounts a
        JOIN account_balances_mat m ON m.account_id = a.account_id
        WHERE m.balance < 0
          AND a.account_type NOT IN ('GOVERNMENT', 'SYSTEM')
          AND (a.allow_overdraft = 0 OR m.balance < -a.credit_limit)) AS illegal_negative_accounts,

    -- Tamper signal (ADT invariants-trigger-bypass-not-detected): the AFTER-posting
    -- triggers always bump account_balances_mat.version, so a row with a non-zero
    -- balance but version = 0 means the balance was written WITHOUT a trigger, i.e.
    -- account_balances_mat was edited outside the ledger. (Assumes the triggers are
    -- the sole writer to that table.)
    (SELECT COUNT(*) FROM account_balances_mat WHERE version = 0 AND balance <> 0) AS mat_balance_without_trigger,

    (SELECT COUNT(*) FROM ledger_postings) AS postings,
    (SELECT COUNT(*) FROM accounts)        AS accounts
) x;
