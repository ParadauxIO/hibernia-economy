import { hasPermission } from '@/lib/auth/policy';
import { buildMetadata } from '@/lib/metadata';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { findPlayerName } from '@/lib/sql/me';
import { PlayerDashboard } from '@/components/PlayerDashboard';

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

  // The /admin layout already gated to admin; this is a privileged inspection of
  // another player's private financial dashboard, so audit it against the player.
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'player.read.all')) return null; // layout renders the access gate
  await auditView(viewer, { path: '/admin/players/[uuid]', targetType: 'player', targetId: uuid });

  const name = await findPlayerName(uuid);

  return (
    <>
      <div className="page-heading">
        <h1>{name ?? 'Unknown player'}</h1>
        <span className="sub mono">{uuid} · last 90 days</span>
        <Link href="/admin/players" className="btn" prefetch={false} style={{ marginLeft: 'auto' }}>
          ← Players
        </Link>
      </div>

      <PlayerDashboard uuid={uuid} name={name} />
    </>
  );
}
