-- Explorer roles are independent bundles. Legacy viewer no longer grants oversight.
INSERT IGNORE INTO explorer_group_capability (group_id, capability)
SELECT group_id, 'player' FROM explorer_group_capability WHERE capability IN ('viewer', 'staff.audit');
DELETE FROM explorer_group_capability WHERE capability IN ('viewer', 'staff.audit');
INSERT IGNORE INTO explorer_role (player_uuid_bin, role, granted_at, granted_by_uuid_bin)
SELECT player_uuid_bin, 'player', granted_at, granted_by_uuid_bin
FROM explorer_role WHERE role IN ('viewer', 'staff.audit');
DELETE FROM explorer_role WHERE role IN ('viewer', 'staff.audit');

-- LP snapshots expire when reconciliation stops, including when LP is absent.
ALTER TABLE explorer_group_member ADD COLUMN synced_at TIMESTAMP NULL;
CREATE TABLE explorer_lp_group_member (
    lp_group VARCHAR(64) NOT NULL,
    player_uuid_bin BINARY(16) NOT NULL,
    synced_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (lp_group, player_uuid_bin),
    KEY idx_explorer_lp_player (player_uuid_bin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One firm-finance relationship rule for firm pages and all account consumers.
CREATE OR REPLACE VIEW firm_financial_access_web AS
SELECT firm_id, proprietor_uuid_bin AS subject_uuid_bin FROM firm
UNION
SELECT fe.firm_id, fe.player_uuid_bin FROM firm_employee fe
JOIN firm_role fr ON fr.role_id = fe.role_id AND fr.firm_id = fe.firm_id AND fr.deleted_at IS NULL
JOIN firm_role_permission rp ON rp.role_id = fr.role_id AND rp.deleted_at IS NULL
WHERE fe.left_at IS NULL AND rp.permission IN ('FINANCIAL', 'ADMIN');

-- Shared relationship rule used by account pages, government and My data.
-- A group relationship grants read access only; API spending/key scopes are unchanged.
CREATE OR REPLACE VIEW account_read_access_web AS
SELECT account_id, subject_uuid_bin FROM account_access
WHERE level IN ('VIEWER', 'MEMBER', 'AUTHORIZER') AND removed_at IS NULL
UNION
SELECT aga.account_id, gm.player_uuid_bin FROM account_group_access aga
JOIN explorer_lp_group_member gm ON gm.lp_group = aga.lp_group
WHERE aga.removed_at IS NULL AND gm.synced_at > CURRENT_TIMESTAMP - INTERVAL 1 HOUR
UNION
SELECT fa.account_id, ff.subject_uuid_bin FROM firm_accounts fa
JOIN firm_financial_access_web ff ON ff.firm_id = fa.firm_id;
