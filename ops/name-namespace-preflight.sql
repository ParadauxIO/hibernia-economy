-- PAR-512: read-only V40 blocker report; run on a consistent V35+ restored snapshot.
-- This is one SELECT, usable with a SELECT-only account. Zero rows means the
-- V40 name guard passes, not that the whole release or Flyway history is ready.
-- Do not run on pre-V35 layouts. Keep names/identities in private operator evidence.
-- Review every reported row; this query never chooses replacement names.
WITH claims AS (
SELECT CONVERT('ACCOUNT' USING utf8mb4) COLLATE utf8mb4_unicode_ci AS source_kind,
       CONVERT(CAST(account_id AS CHAR) USING utf8mb4) COLLATE utf8mb4_unicode_ci AS subject_id,
       CONVERT(TRIM(display_name) USING utf8mb4) COLLATE utf8mb4_unicode_ci AS name_key,
       CONVERT(CASE WHEN account_type='PERSONAL' THEN CONCAT('P:',HEX(owner_uuid_bin)) ELSE CONCAT('A:',account_id) END USING utf8mb4) COLLATE utf8mb4_unicode_ci AS entity_key
FROM accounts WHERE display_name IS NOT NULL
UNION ALL
SELECT CONVERT('FIRM' USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CAST(firm_id AS CHAR) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(TRIM(display_name) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CONCAT('F:',firm_id) USING utf8mb4) COLLATE utf8mb4_unicode_ci
FROM firm WHERE NOT (is_archived=1 AND name_released=1)
UNION ALL
SELECT CONVERT('PLAYER' USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(HEX(player_uuid_bin) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(TRIM(current_name) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CONCAT('P:',HEX(player_uuid_bin)) USING utf8mb4) COLLATE utf8mb4_unicode_ci
FROM economy_players WHERE current_name <> HEX(player_uuid_bin)
UNION ALL
SELECT CONVERT('ALIAS' USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(alias USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(TRIM(alias) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CONCAT('A:',account_id) USING utf8mb4) COLLATE utf8mb4_unicode_ci
FROM firm_shop_alias_reservations
UNION ALL
SELECT CONVERT('CURRENT_ALIAS' USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CAST(account_id AS CHAR) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(TRIM(shop_alias) USING utf8mb4) COLLATE utf8mb4_unicode_ci,
       CONVERT(CONCAT('A:',account_id) USING utf8mb4) COLLATE utf8mb4_unicode_ci
FROM firm_accounts WHERE shop_alias IS NOT NULL
), blockers AS (
    SELECT 'COLLISION' AS blocker, name_key AS name, COUNT(DISTINCT entity_key) AS affected_entities
    FROM claims GROUP BY name_key HAVING COUNT(DISTINCT entity_key) > 1
    UNION ALL
    SELECT 'BLANK_NAME', name_key, COUNT(DISTINCT entity_key)
    FROM claims WHERE name_key = '' GROUP BY name_key
    UNION ALL
    -- V40 checks whitespace even on released firms and UUID-placeholder players.
    SELECT 'ACCOUNT_WHITESPACE', CONVERT(display_name USING utf8mb4) COLLATE utf8mb4_unicode_ci, 1
    FROM accounts WHERE BINARY display_name <> BINARY TRIM(display_name)
    UNION ALL
    SELECT 'FIRM_WHITESPACE', CONVERT(display_name USING utf8mb4) COLLATE utf8mb4_unicode_ci, 1
    FROM firm WHERE BINARY display_name <> BINARY TRIM(display_name)
    UNION ALL
    SELECT 'PLAYER_WHITESPACE', CONVERT(current_name USING utf8mb4) COLLATE utf8mb4_unicode_ci, 1
    FROM economy_players WHERE BINARY current_name <> BINARY TRIM(current_name)
)
SELECT blocker, name, affected_entities FROM blockers ORDER BY blocker, name;
