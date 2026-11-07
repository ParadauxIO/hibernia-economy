import './gov.css';
import type { ReactNode } from 'react';
import { getViewer, hasPermission } from '@/lib/auth';
import { resolveGovScope } from '@/lib/services/governmentScope';
import { GovGate } from './GovGate';
import { GovShell, type GovNavGroup } from './GovShell';

// The /gov workspace (spec §10). The layout resolves the scope once (pages
// reuse it through React cache()) and builds the navigation from permissions.
// Compliance is gated separately: its tools need firm.financials.read.all and
// transactions.read, not government scope, so a compliance officer without
// government.read still reaches /gov/compliance.
export default async function GovLayout({ children }: { children: ReactNode }) {
  const [access, viewer] = await Promise.all([resolveGovScope(), getViewer()]);
  const compliance = hasPermission(viewer, 'firm.financials.read.all') && hasPermission(viewer, 'transactions.read');
  if (!access.ok && !compliance) return <GovGate reason={access.reason} />;

  const nav: GovNavGroup[] = [];
  if (access.ok) {
    nav.push({ items: [
      { label: 'Overview', href: '/gov' },
      { label: 'Accounts', href: '/gov/accounts' },
      { label: 'Revenue', href: '/gov/revenue' },
      { label: 'Spending', href: '/gov/spending' },
      { label: 'Payroll', href: '/gov/payroll' },
      { label: 'Fines', href: '/gov/fines' },
      { label: 'Reports', href: '/gov/reports' },
      ...(access.scope.canAudit ? [{ label: 'Audit', href: '/gov/audit' as const }] : []),
    ] });
  }
  if (compliance) nav.push({ label: 'Compliance', items: [{ label: 'Watchlist', href: '/gov/compliance' }] });
  if (access.ok && access.scope.canManageUnits) nav.push({ label: 'Admin', items: [{ label: 'Manage units', href: '/admin/gov-units' }] });
  return <GovShell nav={nav}>{children}</GovShell>;
}
