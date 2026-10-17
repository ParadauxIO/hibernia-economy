import 'server-only';
import { memo } from '@/lib/cache';
import { getTaxRevenue } from '@/lib/sql/taxation';
import { summarizeTaxRevenue } from '@/lib/taxation';

export const getTaxationDashboard = (days: 7 | 30) => memo(`taxation:v1:${days}`, 60_000, async () => {
  const rows = await getTaxRevenue(days);
  return summarizeTaxRevenue(rows, days, new Date());
});
