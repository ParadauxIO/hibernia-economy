-- PAR-501: read-only candidate inventory, NOT an enforcement or remediation policy.
-- Run against a consistent restored snapshot with migrations through V35 or later.
-- Emits one row per pair, with IDs/lifecycle information for human review.
-- Uses existing utf8mb4_unicode_ci equality, plus a separately labelled surrounding-space
-- trim comparison used by V40. Does not apply blocked-term normalization.
-- For the actual V40 blocking rules use ops/name-namespace-preflight.sql.
-- Includes released firms and historical shop aliases: flags describe existing
-- lifecycle, not approval to reserve/release names under the future global policy.
-- Same-owner PERSONAL/player pairs remain visible; no exemption is presumed.
-- No balances, ownership, names, or references are changed by this SELECT.
WITH claims AS (
    SELECT 'ACCOUNT' AS kind, CAST(account_id AS CHAR) AS entity_id,
           CONVERT(display_name USING utf8mb4) COLLATE utf8mb4_unicode_ci AS display_name,
           HEX(owner_uuid_bin) AS owner_uuid, account_type AS subtype,
           is_archived AS archived, 0 AS released
    FROM accounts WHERE display_name IS NOT NULL
    UNION ALL
    SELECT 'BUSINESS', CAST(firm_id AS CHAR),
           CONVERT(display_name USING utf8mb4) COLLATE utf8mb4_unicode_ci,
           HEX(proprietor_uuid_bin), 'FIRM', is_archived, name_released
    FROM firm
    UNION ALL
    SELECT 'PLAYER', HEX(player_uuid_bin),
           CONVERT(current_name USING utf8mb4) COLLATE utf8mb4_unicode_ci,
           HEX(player_uuid_bin), 'DIRECTORY', 0, 0
    FROM economy_players
    UNION ALL
    SELECT 'SHOP_ALIAS', CONCAT(CAST(r.account_id AS CHAR), ':', r.alias),
           CONVERT(r.alias USING utf8mb4) COLLATE utf8mb4_unicode_ci,
           HEX(a.owner_uuid_bin),
           CASE WHEN fa.shop_alias = r.alias AND fa.removed_at IS NULL
                THEN 'CURRENT_ALIAS' ELSE 'HISTORICAL_ALIAS' END,
           a.is_archived, 0
    FROM firm_shop_alias_reservations r
    JOIN firm_accounts fa ON fa.account_id = r.account_id
    JOIN accounts a ON a.account_id = r.account_id
    UNION ALL
    SELECT 'CURRENT_ALIAS', CAST(fa.account_id AS CHAR),
           CONVERT(fa.shop_alias USING utf8mb4) COLLATE utf8mb4_unicode_ci,
           HEX(a.owner_uuid_bin), 'CURRENT_ALIAS', a.is_archived, 0
    FROM firm_accounts fa JOIN accounts a ON a.account_id = fa.account_id
    WHERE fa.shop_alias IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM firm_shop_alias_reservations r
        WHERE r.alias = fa.shop_alias AND r.account_id = fa.account_id
    )
)
SELECT CASE WHEN l.display_name = r.display_name THEN 'EXISTING_COLLATION'
            ELSE 'TRIM_ONLY' END AS comparison_basis,
       l.kind AS left_kind, l.entity_id AS left_id, l.display_name AS left_name,
       l.subtype AS left_subtype, l.owner_uuid AS left_owner_uuid,
       l.archived AS left_archived, l.released AS left_released,
       r.kind AS right_kind, r.entity_id AS right_id, r.display_name AS right_name,
       r.subtype AS right_subtype, r.owner_uuid AS right_owner_uuid,
       r.archived AS right_archived, r.released AS right_released,
       CASE WHEN l.owner_uuid = r.owner_uuid THEN 1 ELSE 0 END AS same_owner
FROM claims l
JOIN claims r ON TRIM(l.display_name) = TRIM(r.display_name)
             AND (l.kind < r.kind OR (l.kind = r.kind AND l.entity_id < r.entity_id))
ORDER BY l.kind, l.entity_id, r.kind, r.entity_id;
