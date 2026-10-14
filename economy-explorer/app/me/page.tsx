import { hasPermission } from '@/lib/auth/policy';
import { buildMetadata } from '@/lib/metadata';
import Link from 'next/link';
import { getViewer } from '@/lib/auth/viewer';
import { PrivacyGate } from '@/components/PrivacyGate';
import { PlayerDashboard } from '@/components/PlayerDashboard';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: "My data", description: "Your personal {server} economy dashboard — balances and transactions.", path: "/me" });
}

export default async function MyDataPage() {
  const viewer = await getViewer();
  if (!viewer.anon && !hasPermission(viewer, 'player.read')) return <PrivacyGate kind="private" title="Permission required" />;
  if (viewer.anon) {
    return (
      <PrivacyGate
        kind="login"
        title="My data"
        hint="Sign in to see your own accounts, balances, counterparties, and ChestShop activity."
      />
    );
  }
  if (!viewer.linked || !viewer.minecraftUuid) {
    return (
      <PrivacyGate
        kind="link"
        title="Link your Minecraft account"
        hint="You're signed in but not yet linked. Generate a code and run /treasuryapi ui link <code> in-game."
      />
    );
  }

  return (
    <>
      <div className="page-heading">
        <h1>My data</h1>
        <span className="sub">{viewer.minecraftName ?? viewer.minecraftUuid} · last 90 days</span>
        <Link href="/me/webhooks" className="btn" prefetch={false} style={{ marginLeft: 'auto' }}>
          Webhooks →
        </Link>
        <Link href="/me/market" className="btn" prefetch={false} style={{ marginLeft: 8 }}>
          ChestShop activity →
        </Link>
      </div>

      <PlayerDashboard uuid={viewer.minecraftUuid} name={viewer.minecraftName} self />
    </>
  );
}
