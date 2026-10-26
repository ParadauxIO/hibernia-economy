import { TAX_KINDS, TAX_LABELS, type TaxKind } from '@/lib/taxation';
import { TimeSeriesChart } from './TimeSeriesChart';

/** Unstacked lines keep negative fine-refund days visible without hiding other taxes. */
export function TaxRevenueTimeline({ data }: { data: ({ date: string } & Record<TaxKind, number>)[] }) {
  return <TimeSeriesChart data={data} x="date" kind="line" outliers={false} label="Tax revenue by day" height={260}
    series={TAX_KINDS.map((kind) => ({ key: kind, label: TAX_LABELS[kind], format: 'money' as const }))} />;
}
