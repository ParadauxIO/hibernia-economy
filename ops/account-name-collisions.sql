-- PAR-144: read-only counterpart of Treasury's startup/daily account-resolution audit.
-- Uses the current directory, not the retired firm_players table.
-- A redirect is evidence of explicit Vault routing, not evidence that past funds are reconciled.
SELECT g.account_id AS government_id, g.display_name AS government_name,
       HEX(p.player_uuid_bin) AS player_uuid_hex,
       pa.account_id AS personal_id, r.account_id AS redirect_id
FROM accounts g
JOIN economy_players p ON p.current_name = g.display_name
  AND p.current_name <> HEX(p.player_uuid_bin)
LEFT JOIN accounts pa ON pa.owner_uuid_bin = p.player_uuid_bin
  AND pa.account_type = 'PERSONAL' AND pa.is_archived = 0
LEFT JOIN account_redirects r ON r.redirect_uuid_bin = p.player_uuid_bin
WHERE g.account_type = 'GOVERNMENT' AND g.is_archived = 0
ORDER BY g.account_id, pa.account_id;
