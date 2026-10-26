import { parseMoney, toDbString, type Cents } from '@/lib/money';
import { addDays, type DayRange } from '@/lib/rollup/days';

/**
 * Closing balance per day for [range.from, range.to], derived like
 * lib/services/rollup balanceSeries: current balance minus the net of every
 * later day. `nets` must cover range.from + 1 through today.
 */
export function deriveBalances(current: Cents, nets: readonly { day: string; net: string }[], range: DayRange): { day: string; balance: string }[] {
  const netByDay = new Map(nets.map((n) => [n.day, parseMoney(n.net)]));
  let balance = current;
  for (const [day, net] of netByDay) if (day > range.to) balance -= net;
  const out: { day: string; balance: string }[] = [];
  for (let day = range.to; day >= range.from; day = addDays(day, -1)) {
    out.push({ day, balance: toDbString(balance) });
    balance -= netByDay.get(day) ?? 0n;
  }
  return out.reverse();
}

