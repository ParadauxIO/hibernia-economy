import { BarChart } from './BarChart';

/** Money in above the axis, money out below it, per day. */
export function InOutChart({ data }: { data: { day: string; cr: number | string; dr: number | string }[] }) {
  const rows = data.map((d) => ({ day: d.day, in: d.cr, out: -Number(d.dr), outRaw: d.dr }));
  return <BarChart data={rows} x="day" xFormat="day" stacked height={210} label="Money in and out by day" note="Daily buckets in UTC."
    series={[
      { key: 'in', label: 'In', format: 'money', color: 'var(--pos)' },
      { key: 'out', label: 'Out', format: 'money', color: 'var(--neg)', rawKey: 'outRaw' },
    ]} />;
}
