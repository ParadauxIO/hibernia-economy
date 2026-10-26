import { Tabs } from '@democracycraft/design-system/next';

/** Ledger side and explorer side of the audit, keeping the unit and range. */
export function AuditTabs({ current, query }: { current: 'ledger' | 'views'; query: string }) {
  const qs = query ? `?${query}` : '';
  return <Tabs label="Audit views" items={[
    { label: 'Money moved', href: `/gov/audit${qs}`, current: current === 'ledger' },
    { label: 'Who viewed', href: `/gov/audit/views${qs}`, current: current === 'views' },
  ]} />;
}
