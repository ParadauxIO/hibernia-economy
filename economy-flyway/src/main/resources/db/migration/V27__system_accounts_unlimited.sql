-- PAR-319: normalize SYSTEM accounts to the unlimited faucet/sink config.
--
-- SYSTEM accounts mint and burn freely — they ignore credit limits. The intended
-- config is allow_overdraft = 1, credit_limit = -1 (the "-1 disables the check"
-- sentinel), which getOrCreateSystemAccountId sets directly. But createAccount
-- (the TreasuryApi path ChestShop used for its "ChestShop System" account) used to
-- default every type to (allow_overdraft = 0, credit_limit = 0), so that account
-- ended up (allow_overdraft = 1, credit_limit = 0) — floored at 0 by the in-process
-- ledger, while the REST engine treated any allow_overdraft account as unlimited.
-- The two engines disagreed on exactly that account.
--
-- The overdraft check is now SYSTEM-aware in code (OverdraftPolicy) AND createAccount
-- seeds SYSTEM with the -1 sentinel, so new accounts are consistent. This migration
-- brings existing SYSTEM rows in line so the stored data matches both mechanisms.
-- Idempotent: re-running is a no-op once every SYSTEM row is already (1, -1).
--
-- Verified against the live tenant (2026-07-04): the only SYSTEM row not already at
-- the -1 sentinel is account_id = 4 "ChestShop System" (allow_overdraft = 1,
-- credit_limit = 0.00, balance +70.95). The other SYSTEM/faucet accounts already use
-- credit_limit = -1, so this touches one row.
UPDATE accounts
   SET allow_overdraft = 1,
       credit_limit    = -1
 WHERE account_type = 'SYSTEM'
   AND (credit_limit IS NULL OR credit_limit >= 0);
