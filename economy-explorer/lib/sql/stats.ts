import 'server-only';
import { sql } from 'kysely';
import { db } from '@/lib/db';

// Current-balance statistics over account_balances_mat (one row per account,
// ~146k rows): cheap enough for the request path, and memoised by
// lib/services/economy.ts. Activity over time (volume, heatmap, flows) is read
// from the rollups (lib/services/rollup.ts), never from the ledger here.

export interface BalanceBucket { bucket: string; bucket_label: string; account_count: number }

const BUCKET_LABEL: Record<string, string> = {
  neg: '< 0',
  '0_100': '0 – 100',
  '100_500': '100 – 500',
  '500_1k': '500 – 1K',
  '1k_2k5': '1K – 2.5K',
  '2k5_5k': '2.5K – 5K',
  '5k_10k': '5K – 10K',
  '10k_25k': '10K – 25K',
  '25k_50k': '25K – 50K',
  '50k_100k': '50K – 100K',
  '100k_plus': '> 100K',
};

/**
 * Mirrors LedgerExplorerMapper.getBalanceDistribution (line 244-258), but
 * excludes zero-balance accounts: the server has thousands of empty personal
 * accounts and a giant '0' bar flattens every other bucket. Negatives are kept
 * — they're rare and worth seeing.
 */
export async function getBalanceDistribution(): Promise<BalanceBucket[]> {
  const r = await sql<{ bucket: string; account_count: string | number }>`
    SELECT bucket, COUNT(*) AS account_count FROM (
      SELECT CASE
        WHEN abm.balance < 0       THEN 'neg'
        WHEN abm.balance < 100     THEN '0_100'
        WHEN abm.balance < 500     THEN '100_500'
        WHEN abm.balance < 1000    THEN '500_1k'
        WHEN abm.balance < 2500    THEN '1k_2k5'
        WHEN abm.balance < 5000    THEN '2k5_5k'
        WHEN abm.balance < 10000   THEN '5k_10k'
        WHEN abm.balance < 25000   THEN '10k_25k'
        WHEN abm.balance < 50000   THEN '25k_50k'
        WHEN abm.balance < 100000  THEN '50k_100k'
        ELSE '100k_plus'
      END AS bucket, abm.balance
      FROM account_balances_mat abm
      JOIN accounts a ON a.account_id = abm.account_id
      WHERE a.is_archived = 0 AND a.account_type = 'PERSONAL' AND abm.balance <> 0
    ) t GROUP BY bucket ORDER BY MIN(balance)
  `.execute(db);
  return r.rows.map((row) => ({
    bucket: row.bucket,
    bucket_label: BUCKET_LABEL[row.bucket] ?? row.bucket,
    account_count: Number(row.account_count),
  }));
}

/**
 * Actual positive personal balances (one number per account) for an exact
 * gini / top-share — replaces the bucket-midpoint synthesis, which flattened
 * the whole top tail onto a single 200k midpoint and understated concentration.
 * ~8k rows, one numeric column — cheap to scan.
 */
export async function getPersonalBalances(): Promise<number[]> {
  const r = await sql<{ b: string }>`
    SELECT abm.balance AS b
    FROM account_balances_mat abm
    JOIN accounts a ON a.account_id = abm.account_id
    WHERE a.is_archived = 0 AND a.account_type = 'PERSONAL' AND abm.balance > 0
  `.execute(db);
  return r.rows.map((row) => parseFloat(row.b)).filter((n) => Number.isFinite(n));
}
