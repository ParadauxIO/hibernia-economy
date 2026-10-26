import { BarChart } from './BarChart';

/** Accounts per balance band. One colour: the bands are ordered, not categories. */
export function BalanceDistribution({ data }: { data: { bucket: string; bucket_label: string; account_count: number }[] }) {
  return <BarChart data={data} x="bucket_label" angleLabels height={250} label="Accounts by balance band"
    series={[{ key: 'account_count', label: 'Accounts', format: 'count', color: 'var(--cat-1)' }]} />;
}
