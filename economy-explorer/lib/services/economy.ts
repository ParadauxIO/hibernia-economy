import 'server-only';
import { memo } from '@/lib/cache';
import { getEconomyStats } from '@/lib/sql/ledger';
import { getBalanceDistribution, getPersonalBalances, getHourHeatmap } from '@/lib/sql/stats';
import { getDailyVolume, getActivePlayersSummary, getNewPlayersDaily, getActivityComparison } from '@/lib/sql/health';
import { gini, topShare } from '@/lib/derived';
export const getWealthData = () => memo('wealth-dashboard:v1', 60_000, async () => {
  const [stats, distribution, balances] = await Promise.all([getEconomyStats(), getBalanceDistribution(), getPersonalBalances()]);
  return { stats, distribution, giniValue: balances.length ? gini(balances) : null,
    top1: balances.length ? topShare(balances, .01) : null, top10: balances.length ? topShare(balances, .1) : null };
});
export const getOverviewData = (days: number) => memo(`economy-overview:v1:${days}`, 60_000, async () => {
  const [stats, volume, players, newPlayers, comparison, heat] = await Promise.all([
    getEconomyStats(), getDailyVolume(days), getActivePlayersSummary(), getNewPlayersDaily(days), getActivityComparison(days), getHourHeatmap(days),
  ]);
  return { stats, volume, players, newPlayers: newPlayers.reduce((n, p) => n + p.count, 0), comparison, heat };
});
