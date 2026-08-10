import 'server-only';
import { sql } from 'kysely';
import { db } from '@/lib/db';

export interface DailyVolumePoint { date: string; txn_count: number; total_volume: string }
export interface DailyDeltaByType { date: string; account_type: string; balance: string }
export interface ActivePlayers { active1d: number; active7d: number; active30d: number; registered: number }
export interface DayCount { date: string; count: number }

/** Mirrors LedgerExplorerMapper.getDailyVolume (line 368-376). */
export async function getDailyVolume(days: number): Promise<DailyVolumePoint[]> {
  const r = await sql<{ txn_date: string; txn_count: string | number; total_volume: string }>`
    SELECT DATE_FORMAT(DATE(lt.settlement_time), '%Y-%m-%d') AS txn_date,
           COUNT(DISTINCT lt.txn_id) AS txn_count,
           COALESCE(SUM(CASE WHEN lp.amount > 0 THEN lp.amount ELSE 0 END), 0.00) AS total_volume
    FROM ledger_txns lt
    LEFT JOIN ledger_postings lp ON lp.txn_id = lt.txn_id
    WHERE lt.settlement_time >= NOW() - INTERVAL ${days} DAY
    GROUP BY DATE(lt.settlement_time)
    ORDER BY txn_date ASC
  `.execute(db);
  return r.rows.map((row) => ({
    date: row.txn_date,
    txn_count: Number(row.txn_count),
    total_volume: row.total_volume,
  }));
}

/** Mirrors LedgerExplorerMapper.getDailyDeltaByType (line 383-392). */
export async function getDailyDeltaByType(days: number): Promise<DailyDeltaByType[]> {
  const r = await sql<DailyDeltaByType>`
    SELECT DATE_FORMAT(DATE(lt.settlement_time), '%Y-%m-%d') AS date,
           a.account_type AS account_type,
           COALESCE(SUM(lp.amount), 0.00) AS balance
    FROM ledger_txns lt
    JOIN ledger_postings lp ON lp.txn_id = lt.txn_id
    JOIN accounts a ON a.account_id = lp.account_id
    WHERE lt.settlement_time >= NOW() - INTERVAL ${days} DAY
    GROUP BY DATE(lt.settlement_time), a.account_type
    ORDER BY date ASC
  `.execute(db);
  return r.rows;
}

/** Mirrors LedgerExplorerMapper.getActivePlayersSummary (line 395-400). */
export async function getActivePlayersSummary(): Promise<ActivePlayers> {
  const r = await sql<{ active1d: string | number; active7d: string | number; active30d: string | number; registered: string | number }>`
    SELECT
      (SELECT COUNT(*) FROM economy_players WHERE last_login_epoch >= UNIX_TIMESTAMP() - 86400) AS active1d,
      (SELECT COUNT(*) FROM economy_players WHERE last_login_epoch >= UNIX_TIMESTAMP() - 7*86400) AS active7d,
      (SELECT COUNT(*) FROM economy_players WHERE last_login_epoch >= UNIX_TIMESTAMP() - 30*86400) AS active30d,
      (SELECT COUNT(*) FROM economy_players) AS registered
  `.execute(db);
  const row = r.rows[0];
  return {
    active1d: Number(row?.active1d ?? 0),
    active7d: Number(row?.active7d ?? 0),
    active30d: Number(row?.active30d ?? 0),
    registered: Number(row?.registered ?? 0),
  };
}

/** Mirrors LedgerExplorerMapper.getNewPlayersDaily (line 403-408). */
export async function getNewPlayersDaily(days: number): Promise<DayCount[]> {
  const r = await sql<{ date: string; count: string | number }>`
    SELECT DATE_FORMAT(DATE(first_seen), '%Y-%m-%d') AS date, COUNT(*) AS count
    FROM economy_players
    WHERE first_seen >= NOW() - INTERVAL ${days} DAY
    GROUP BY DATE(first_seen)
    ORDER BY date ASC
  `.execute(db);
  return r.rows.map((row) => ({ date: row.date, count: Number(row.count) }));
}
