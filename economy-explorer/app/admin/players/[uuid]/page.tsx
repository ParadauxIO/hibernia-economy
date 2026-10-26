import { notFound } from 'next/navigation';
import { PageHeader, PlayerHead } from '@democracycraft/design-system';
import { BackLink } from '@democracycraft/design-system/next';
import { PlayerDashboard } from '@/components/PlayerDashboard';
import { hasPermission } from '@/lib/auth/policy';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { buildMetadata } from '@/lib/metadata';
import { parseRange } from '@/lib/range';
import { findPlayerName } from '@/lib/sql/me';
import { AdminGate } from '../../_components/AdminGate';

export const dynamic = 'force-dynamic';

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export async function generateMetadata({ params }: { params: Promise<{ uuid: string }> }) {
  const { uuid } = await params;
  return buildMetadata({ title: 'Player data', description: 'Admin view of a player\'s personal {server} economy data.', path: `/admin/players/${uuid}` });
}

export default async function AdminPlayerDataPage({ params }: { params: Promise<{ uuid: string }> }) {
  const { uuid: raw } = await params;
  const uuid = raw.toLowerCase();
  if (!UUID_RE.test(uuid)) notFound();

  // A privileged inspection of another player's private financial dashboard, so
  // it is audited against the player.
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'player.read.all')) return <AdminGate viewer={viewer} title="Player lookup access required" permission="player.read.all" />;
  await auditView(viewer, { path: '/admin/players/[uuid]', targetType: 'player', targetId: uuid });

  const name = await findPlayerName(uuid);
  const NINETY_DAYS = parseRange({}, { defaultPreset: '90d' });

  return (
    <>
      <BackLink href="/admin/players" label="Players" />
      <PageHeader
        avatar={<PlayerHead uuid={uuid} size={40} name={name ?? undefined} />}
        title={name ?? 'Unknown player'}
        subtitle="Personal economy data for the last 90 days. This view is recorded in the audit log."
        meta={<span className="mono">{uuid}</span>}
      />
      <PlayerDashboard uuid={uuid} name={name} range={NINETY_DAYS.range} rangeLabel={NINETY_DAYS.label} />
    </>
  );
}
