-- ============================================================================
-- RE-RUNNABLE backfill: align every active firm corporate account with the
-- firm's current proprietor.
--
-- Fixes the changed-proprietor stranding from both un-deployed paths -- player
-- transfer PAR-141 and /firm admin set proprietor PAR-315. Restores the
-- invariant: for every active firm account, the Treasury owner, an active
-- member, and an active authorizer all equal the firm's current proprietor, and
-- the previous owner's stale access is dropped unless they are a current employee.
--
-- IDEMPOTENT and SAFE TO RE-RUN. firm.proprietor_uuid_bin is the source of truth.
-- Each statement is standalone, has its own WHERE, and touches only accounts that
-- are still mismatched -- so re-runs are cheap and safe to schedule hourly until
-- PAR-141/PAR-315 ship, then stop.
--
-- No temp table, no transaction, no session SET, so it runs cleanly in any SQL
-- client or from the CLI. Statement ORDER matters: owner reassignment is LAST so
-- the earlier steps still see the mismatch. Run the whole file top to bottom.
--
-- To hold back the 3 admin-path firms, add this to EACH WHERE below:
--     AND f.firm_id NOT IN (3113,3122,3144)
-- To leave previous owners' access untouched, skip statements 3 and 4.
-- ============================================================================

-- 1. Make the current proprietor an ACTIVE member of each stranded firm account.
INSERT INTO account_members (account_id, member_uuid_bin, added_by_uuid_bin)
SELECT fa.account_id, f.proprietor_uuid_bin, f.proprietor_uuid_bin
FROM firm f
JOIN firm_accounts fa ON fa.firm_id = f.firm_id AND fa.removed_at IS NULL
JOIN accounts a       ON a.account_id = fa.account_id AND a.is_archived = 0
WHERE f.is_archived = 0
  AND a.owner_uuid_bin <> f.proprietor_uuid_bin
ON DUPLICATE KEY UPDATE left_at = NULL;

-- 2. Make the current proprietor an ACTIVE authorizer of each stranded account.
INSERT INTO account_authorizers (account_id, authorizer_uuid_bin, added_by_uuid_bin)
SELECT fa.account_id, f.proprietor_uuid_bin, f.proprietor_uuid_bin
FROM firm f
JOIN firm_accounts fa ON fa.firm_id = f.firm_id AND fa.removed_at IS NULL
JOIN accounts a       ON a.account_id = fa.account_id AND a.is_archived = 0
WHERE f.is_archived = 0
  AND a.owner_uuid_bin <> f.proprietor_uuid_bin
ON DUPLICATE KEY UPDATE revoked_at = NULL;

-- 3. Revoke stale authorizers on stranded accounts: anyone who is neither the
--    proprietor nor a current employee -- i.e. the previous owner.
UPDATE account_authorizers auth
JOIN firm_accounts fa ON fa.account_id = auth.account_id AND fa.removed_at IS NULL
JOIN firm f           ON f.firm_id = fa.firm_id
JOIN accounts a       ON a.account_id = auth.account_id AND a.is_archived = 0
SET auth.revoked_at = CURRENT_TIMESTAMP
WHERE f.is_archived = 0
  AND a.owner_uuid_bin <> f.proprietor_uuid_bin
  AND auth.revoked_at IS NULL
  AND auth.authorizer_uuid_bin <> f.proprietor_uuid_bin
  AND NOT EXISTS (SELECT 1 FROM firm_employee fe
                   WHERE fe.firm_id = f.firm_id
                     AND fe.player_uuid_bin = auth.authorizer_uuid_bin
                     AND fe.is_current = 1);

-- 4. Same, for stale members.
UPDATE account_members mem
JOIN firm_accounts fa ON fa.account_id = mem.account_id AND fa.removed_at IS NULL
JOIN firm f           ON f.firm_id = fa.firm_id
JOIN accounts a       ON a.account_id = mem.account_id AND a.is_archived = 0
SET mem.left_at = CURRENT_TIMESTAMP
WHERE f.is_archived = 0
  AND a.owner_uuid_bin <> f.proprietor_uuid_bin
  AND mem.left_at IS NULL
  AND mem.member_uuid_bin <> f.proprietor_uuid_bin
  AND NOT EXISTS (SELECT 1 FROM firm_employee fe
                   WHERE fe.firm_id = f.firm_id
                     AND fe.player_uuid_bin = mem.member_uuid_bin
                     AND fe.is_current = 1);

-- 5. LAST: reassign each stranded account's owner to the current proprietor.
UPDATE accounts a
JOIN firm_accounts fa ON fa.account_id = a.account_id AND fa.removed_at IS NULL
JOIN firm f           ON f.firm_id = fa.firm_id
SET a.owner_uuid_bin = f.proprietor_uuid_bin
WHERE f.is_archived = 0
  AND a.is_archived = 0
  AND a.owner_uuid_bin <> f.proprietor_uuid_bin;
