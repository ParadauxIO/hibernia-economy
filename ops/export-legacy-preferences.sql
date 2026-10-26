-- Run against the legacy ChestShopDatabase DB with writes stopped.
-- mariadb --batch --raw --skip-column-names legacy_db < ops/export-legacy-preferences.sql > legacy-preferences.jsonl
SELECT JSON_OBJECT('player_uuid', CAST(player_id AS CHAR), 'visible', IF(visible, JSON_EXTRACT('true', '$'), JSON_EXTRACT('false', '$')))
FROM PreviewPreference WHERE visible IS NOT NULL ORDER BY player_id;
