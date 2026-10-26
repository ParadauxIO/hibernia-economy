import 'server-only';
import { memo } from '@/lib/cache';
import type { DayRange } from '@/lib/range';
import { getTaxRevenue } from '@/lib/sql/taxation';
import { summarizeTaxRevenue, type TaxationSummary } from '@/lib/taxation';
import { coverageNow, coveredPart, jobCoverage } from '@/lib/services/economy';
import type { RangeCoverage } from '@/lib/services/rollup';

export type TaxationDashboard = TaxationSummary & { coverage: RangeCoverage };

/**
 * Public tax and fine revenue over `range`, from the account_day rollup: one
 * primary-key range read per government account. Keyed by the rollup's last
 * write, so new collections show on the first request after each tick.
 */
export async function getTaxationDashboard(range: DayRange): Promise<TaxationDashboard> {
  const map = await coverageNow();
  return memo(`taxation:v2:${range.from}:${range.to}:${map.account_day?.updated_at ?? 'none'}`, 5 * 60_000, async () => {
    const coverage = jobCoverage(map, 'account_day', range);
    const part = coveredPart(coverage, range);
    const rows = part ? await getTaxRevenue(part.from, part.to) : [];
    return { coverage, ...summarizeTaxRevenue(rows, part) };
  });
}
