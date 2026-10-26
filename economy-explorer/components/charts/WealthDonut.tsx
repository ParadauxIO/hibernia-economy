import { formatNum } from '@democracycraft/design-system/format';
import { Donut } from './Donut';

const TYPES: Record<string, { label: string; color: string }> = {
  PERSONAL: { label: 'Personal', color: 'var(--cat-1)' },
  BUSINESS: { label: 'Business', color: 'var(--cat-3)' },
  GOVERNMENT: { label: 'Government', color: 'var(--cat-4)' },
  SYSTEM: { label: 'System', color: 'var(--cat-8)' },
};

/** Positive balances by account type. Negative totals (minting accounts) are in the table, not the ring. */
export function WealthDonut({ data }: { data: { account_type: string; account_count: number; total_balance: string }[] }) {
  return <Donut label="Balance by account type" format="money"
    // Slices are named by the type key ("BUSINESS: $61,022,781.54, 1,212 accounts").
    sliceName={(item, formatted) => `${item.key}: ${formatted}, ${item.detail ?? ''}`}
    items={data.map((d) => ({
      key: d.account_type,
      label: TYPES[d.account_type]?.label ?? d.account_type,
      value: d.total_balance,
      color: TYPES[d.account_type]?.color ?? 'var(--fg-muted)',
      detail: `${formatNum(d.account_count)} accounts`,
    }))} />;
}
