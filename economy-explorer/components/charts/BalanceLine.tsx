import { TimeSeriesChart } from './TimeSeriesChart';

/** An account's end-of-day balance. Minting accounts are deeply negative; the axis fits their labels. */
export function BalanceLine({ data }: { data: { date: string; balance: number | string }[] }) {
  return <TimeSeriesChart data={data} x="date" label="Balance by day" height={230}
    series={[{ key: 'balance', label: 'Balance', format: 'money', color: 'var(--cat-1)' }]} />;
}
