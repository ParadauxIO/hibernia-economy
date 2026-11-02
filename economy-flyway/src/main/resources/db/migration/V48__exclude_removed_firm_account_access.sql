-- Historical firm links remain available to admin reports but cannot grant access.
CREATE OR REPLACE VIEW account_read_access_web AS
SELECT account_id, subject_uuid_bin FROM account_access
WHERE level IN ('VIEWER', 'MEMBER', 'AUTHORIZER') AND removed_at IS NULL
UNION
SELECT aga.account_id, gm.player_uuid_bin FROM account_group_access aga
JOIN explorer_lp_group_member gm ON gm.lp_group = aga.lp_group
WHERE aga.removed_at IS NULL AND gm.synced_at > CURRENT_TIMESTAMP - INTERVAL 1 HOUR
UNION
SELECT fa.account_id, ff.subject_uuid_bin FROM firm_accounts fa
JOIN firm_financial_access_web ff ON ff.firm_id = fa.firm_id
WHERE fa.removed_at IS NULL;
