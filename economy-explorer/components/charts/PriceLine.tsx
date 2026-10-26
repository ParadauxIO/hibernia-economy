import { TimeSeriesChart } from './TimeSeriesChart';

/** Average unit price per day. Lines (not areas): a price has no meaningful zero fill. */
export function PriceLine({ data }: { data: { day: string; avg_unit_price: string; sales: number }[] }) {
  return <TimeSeriesChart data={data} x="day" kind="line" label="Average unit price by day" height={230}
    series={[{ key: 'avg_unit_price', label: 'Average price', format: 'money', color: 'var(--cat-1)' }]} />;
}
