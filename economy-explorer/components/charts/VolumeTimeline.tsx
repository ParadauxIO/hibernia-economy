import { TimeSeriesChart } from './TimeSeriesChart';

export interface VolumeTimelineProps {
  countLabel?: 'Transactions' | 'Sales';
  data: { date: string; txn_count: number; total_volume: string }[];
}

/**
 * Daily volume (left axis, money) and count (right axis). Each axis is labelled
 * and its ticks coloured with its series. Phones start with volume only; the
 * legend toggles either series. One outlier day is clamped with a flag.
 */
export function VolumeTimeline({ data, countLabel = 'Transactions' }: VolumeTimelineProps) {
  return <TimeSeriesChart data={data} x="date" label={`${countLabel} and volume by day`}
    note="Daily buckets in UTC. The first and current day may be partial."
    empty="No recorded activity in this period. Try another time window."
    series={[
      { key: 'total_volume', label: 'Volume', format: 'money', axis: 'left', color: 'var(--cat-1)' },
      { key: 'txn_count', label: countLabel, format: 'count', axis: 'right', color: 'var(--cat-3)', hideOnNarrow: true },
    ]} />;
}
