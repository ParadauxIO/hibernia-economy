-- ============================================================================
-- RE-RUNNABLE backfill: align every active firm corporate account with the
-- firm's current proprietor.
--
-- Repairs firm accounts stranded by a proprietor change made BEFORE the fixed
-- code shipped -- player transfer (PAR-141) and /firm admin set proprietor
-- (PAR-315). The plugins now reassign accounts themselves on every proprietor
-- change, but nothing repairs rows that were already stranded, so run this ONCE
-- after the release is deployed (see wiki/release-runbook.md). Restores the
-- invariant: for every active firm account, the Treasury owner and an active
-- AUTHORIZER grant both equal the firm's current proprietor, and the previous
-- owner's stale access is dropped unless they are a current employee.
--
-- SCHEMA: requires V18+ (the consolidated account_access table, one row per
-- (account, subject) with level VIEWER < MEMBER < AUTHORIZER, soft-deleted via
-- removed_at). The pre-V18 account_members / account_authorizers tables this
-- script used to write no longer exist; do not run an older copy of it after
-- V18 has been applied -- it fails on its first statement.
--
-- IDEMPOTENT and SAFE TO RE-RUN. firm.proprietor_uuid_bin is the source of truth.
-- Each statement is standalone, has its own WHERE, and touches only accounts that
-- are still mismatched -- a second run changes nothing.
--
-- No temp table, no transaction, no session SET, so it runs cleanly in any SQL
-- client or from the CLI. Statement ORDER matters: owner reassignment is LAST so
-- the earlier steps still see the mismatch. Run the whole file top to bottom.
--
-- To hold back the 3 admin-path firms, add this to EACH WHERE below:
--     AND f.firm_id NOT IN (3113,3122,3144)
-- To leave previous owners' access untouched, skip statement 2.
-- ============================================================================

-- 1. Make the current proprietor an ACTIVE AUTHORIZER of each stranded firm
--    account (AUTHORIZER implies member). Revives a soft-deleted row and raises
--    a lower level, mirroring MembershipMapper.addMember + promoteToAuthorizer.
INSERT INTO account_access (account_id, subject_uuid_bin, level, added_by_uuid_bin)
SELECT fa.account_id, f.proprietor_uuid_bin, 'AUTHORIZER', f.proprietor_uuid_bin
FROM firm f
JOIN firm_accounts fa ON fa.firm_id = f.firm_id AND fa.removed_at IS NULL
JOIN accounts a       ON a.account_id = fa.account_id AND a.is_archived = 0
WHERE f.is_archived = 0
  AND a.owner_uuid_bin <> f.proprietor_uuid_bin
ON DUPLICATE KEY UPDATE level = 'AUTHORIZER', removed_at = NULL;

-- 2. Revoke stale MEMBER/AUTHORIZER access on stranded accounts: anyone who is
--    neither the proprietor nor a current employee -- i.e. the previous owner.
--    Pure VIEWER grants are left alone (as MembershipMapper.removeMember does).
UPDATE account_access aa
JOIN firm_accounts fa ON fa.account_id = aa.account_id AND fa.removed_at IS NULL
JOIN firm f           ON f.firm_id = fa.firm_id
JOIN accounts a       ON a.account_id = aa.account_id AND a.is_archived = 0
SET aa.removed_at = CURRENT_TIMESTAMP
WHERE f.is_archived = 0
  AND a.owner_uuid_bin <> f.proprietor_uuid_bin
  AND aa.removed_at IS NULL
  AND aa.level IN ('MEMBER','AUTHORIZER')
  AND aa.subject_uuid_bin <> f.proprietor_uuid_bin
  AND NOT EXISTS (SELECT 1 FROM firm_employee fe
                   WHERE fe.firm_id = f.firm_id
                     AND fe.player_uuid_bin = aa.subject_uuid_bin
                     AND fe.is_current = 1);

-- 3. LAST: reassign each stranded account's owner to the current proprietor.
UPDATE accounts a
JOIN firm_accounts fa ON fa.account_id = a.account_id AND fa.removed_at IS NULL
JOIN firm f           ON f.firm_id = fa.firm_id
SET a.owner_uuid_bin = f.proprietor_uuid_bin
WHERE f.is_archived = 0
  AND a.is_archived = 0
  AND a.owner_uuid_bin <> f.proprietor_uuid_bin;
