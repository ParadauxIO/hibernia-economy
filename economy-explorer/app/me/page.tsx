import { PageHeader, PlayerHead } from '@democracycraft/design-system';
import { RangePicker } from '@democracycraft/design-system/next';
import { PlayerDashboard } from '@/components/PlayerDashboard';
import { getViewer } from '@/lib/auth/viewer';
import { buildMetadata } from '@/lib/metadata';
import { parseRange, toDay } from '@/lib/range';
import { meGate } from '@/lib/services/me';
import { MeGateView } from './_components/MeGateView';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'My finances', description: 'Your own {server} money: balance, income and spending, accounts you can access and recent activity.', path: '/me' });
}

export default async function MyFinancesPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  const gate = meGate(viewer);
  if (!gate.ok) return <MeGateView kind={gate.kind} />;
  const range = parseRange(await searchParams, { defaultPreset: '90d' });
  return <>
    <PageHeader avatar={<PlayerHead uuid={gate.uuid} size={48} />} eyebrow={<>Signed in as {gate.name ?? 'you'}</>} title="Overview"
      subtitle="Your personal money. Firm and government accounts you can access are listed separately and never added to it." />
    <RangePicker value={range.value} allowCompare={false} presets={['30d', '90d', 'ytd', '1y']} max={toDay(new Date())} />
    <PlayerDashboard uuid={gate.uuid} name={gate.name} self range={range.range} rangeLabel={range.label} />
  </>;
}
