import 'server-only';
import { sql } from 'kysely';
import { db, binToUuid } from '@/lib/db';

export interface SearchResult {
  kind: 'account' | 'firm' | 'player';
  label: string;
  secondary: string | null;
  account_id: number | null;
  account_type: string | null;
  balance: string | null;
  firm_id: number | null;
  firm_name: string | null;
  player_uuid: string | null;
  player_name: string | null;
}

/** Mirrors LedgerExplorerMapper.searchGlobal (line 521-557). */
export async function searchGlobal(q: string, limit: number): Promise<SearchResult[]> {
  const r = await sql<{
    kind: 'account' | 'firm' | 'player';
    label: string;
    secondary: string | null;
    accountId: number | null;
    accountType: string | null;
    balance: string | null;
    firmId: number | null;
    firmName: string | null;
    playerUuidBin: Buffer | null;
    playerName: string | null;
  }>`
    (SELECT 'account' AS kind, COALESCE(fp.current_name, a.display_name,
              CONCAT('Account #', a.account_id)) AS label,
              CASE WHEN fp.current_name IS NOT NULL AND a.display_name IS NOT NULL
                        AND fp.current_name != a.display_name
                   THEN a.display_name
                   ELSE a.account_type END AS secondary,
              a.account_id AS accountId, a.account_type AS accountType,
              COALESCE(abm.balance, 0.00) AS balance,
              NULL AS firmId, NULL AS firmName,
              NULL AS playerUuidBin, NULL AS playerName
      FROM accounts a
      LEFT JOIN economy_players fp ON fp.player_uuid_bin = a.owner_uuid_bin
      LEFT JOIN account_balances_mat abm ON abm.account_id = a.account_id
      WHERE a.is_archived = 0
        AND (a.display_name LIKE CONCAT('%', ${q}, '%')
          OR fp.current_name LIKE CONCAT('%', ${q}, '%')
          OR CAST(a.account_id AS CHAR) = ${q})
      LIMIT ${limit})
    UNION ALL
    (SELECT 'firm' AS kind, f.display_name AS label, COALESCE(f.hq_region, '—') AS secondary,
            NULL AS accountId, NULL AS accountType, NULL AS balance,
            f.firm_id AS firmId, f.display_name AS firmName,
            NULL AS playerUuidBin, NULL AS playerName
      FROM firm f
      WHERE f.is_archived = 0
        AND (f.display_name LIKE CONCAT('%', ${q}, '%') OR f.hq_region LIKE CONCAT('%', ${q}, '%'))
      LIMIT ${limit})
    UNION ALL
    (SELECT 'player' AS kind, fp.current_name AS label,
            DATE_FORMAT(fp.first_seen, '%Y-%m-%d') AS secondary,
            NULL AS accountId, NULL AS accountType, NULL AS balance,
            NULL AS firmId, NULL AS firmName,
            fp.player_uuid_bin AS playerUuidBin, fp.current_name AS playerName
      FROM economy_players fp
      WHERE fp.current_name LIKE CONCAT('%', ${q}, '%')
      LIMIT ${limit})
  `.execute(db);
  return r.rows.map((row) => ({
    kind: row.kind,
    label: row.label,
    secondary: row.secondary,
    account_id: row.accountId,
    account_type: row.accountType,
    balance: row.balance,
    firm_id: row.firmId,
    firm_name: row.firmName,
    player_uuid: row.playerUuidBin ? binToUuid(row.playerUuidBin) : null,
    player_name: row.playerName,
  }));
}
