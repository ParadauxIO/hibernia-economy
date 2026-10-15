import { hasPermission } from '@/lib/auth/policy';
import { buildMetadata } from '@/lib/metadata';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { PrivacyGate } from '@/components/PrivacyGate';
import { findForOwner, listRecentDeliveries } from '@/lib/sql/webhook';
import { fmtN } from '@/lib/format';
import { LocalTimestamp } from '@/components/LocalTimestamp';
import { RotateSecretButton } from './rotate-secret-button';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Webhook deliveries', description: 'Delivery health for one of your {server} webhooks.', path: '/me/webhooks' });
}

function statusBadge(status: string) {
  if (status === 'DELIVERED') return <span className="badge badge-active">delivered</span>;
  if (status === 'FAILED') return <span className="badge badge-archived">failed</span>;
  return <span className="badge">pending</span>;
}

export default async function WebhookDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id: idStr } = await params;
  const id = Number(idStr);

  const viewer = await getViewer();
  if (!viewer.anon && !hasPermission(viewer, 'webhooks.read')) return <PrivacyGate kind="private" title="Permission required" />;
  if (viewer.anon) return <PrivacyGate kind="login" title="Webhook deliveries" hint="Sign in to view your webhook delivery history." />;
  if (!viewer.linked || !viewer.minecraftUuid) {
    return <PrivacyGate kind="link" title="Link your Minecraft account" hint="You're signed in but not yet linked." />;
  }
  if (!Number.isInteger(id) || id <= 0) notFound();

  const hook = await findForOwner(id, viewer.minecraftUuid);
  if (!hook) notFound();
  await auditView(viewer, { method: 'GET', path: '/me/webhooks/[id]', targetType: 'player', targetId: `webhook:${id}` });

  const deliveries = await listRecentDeliveries(id, viewer.minecraftUuid, 50);

  return (
    <>
      <div className="page-heading">
        <h1>Webhook deliveries</h1>
        <span className="sub mono">{hook.url}</span>
        <Link href="/me/webhooks" className="btn" prefetch={false} style={{ marginLeft: 'auto' }}>
          ← Webhooks
        </Link>
      </div>

      <div className="card">
        <div className="card-title">Subscription</div>
        <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', alignItems: 'center' }}>
          <span className="badge">{hook.scope === 'firm' ? `firm #${hook.firmId}` : `account #${hook.accountId}`}</span>
          {hook.isDiscord && <span className="badge badge-active" title="Deliveries are sent as a rich Discord embed">Discord embed</span>}
          {hook.active
            ? <span className="badge badge-active">active</span>
            : <span className="badge badge-archived">{hook.disabledAt ? 'auto-disabled' : 'paused'}</span>}
          <span className="muted small">consecutive failures: <span className="mono">{fmtN(hook.consecutiveFailures)}</span></span>
          {hook.disabledAt && <span className="muted small">disabled <LocalTimestamp value={hook.disabledAt} /></span>}
          <span style={{ marginLeft: 'auto' }}>{hasPermission(viewer, 'webhooks.manage') && <RotateSecretButton id={hook.id} />}</span>
        </div>
      </div>

      <div className="card">
        <div className="card-title">Recent deliveries <span className="sub">latest 50</span></div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Txn</th>
                <th className="amount">Account</th>
                <th>Status</th>
                <th className="amount">Attempts</th>
                <th className="amount">HTTP</th>
                <th>Last error</th>
                <th className="ts">Next attempt</th>
                <th className="ts">Created</th>
              </tr>
            </thead>
            <tbody>
              {deliveries.length === 0 && <tr><td colSpan={8} className="empty">No deliveries yet.</td></tr>}
              {deliveries.map((d) => (
                <tr key={d.deliveryId}>
                  <td className="mono">{d.txnId}</td>
                  <td className="amount mono">#{d.accountId}</td>
                  <td>{statusBadge(d.status)}</td>
                  <td className="amount mono">{fmtN(d.attempts)}</td>
                  <td className="amount mono">{d.httpStatus ?? <span className="muted">—</span>}</td>
                  <td>{d.lastError ? <span className="small">{d.lastError}</span> : <span className="muted">—</span>}</td>
                  <td className="ts">{d.status === 'PENDING' ? <LocalTimestamp value={d.nextAttemptAt} /> : <span className="muted">—</span>}</td>
                  <td className="ts"><LocalTimestamp value={d.createdAt} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}
