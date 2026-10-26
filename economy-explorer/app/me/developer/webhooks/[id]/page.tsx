import { hasPermission } from '@/lib/auth/policy';
import { buildMetadata } from '@/lib/metadata';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { meGate } from '@/lib/services/me';
import { MeGateView } from '../../../_components/MeGateView';
import { findForOwner, listRecentDeliveries } from '@/lib/sql/webhook';
import { fmtN } from '@/lib/format';
import { LocalTimestamp } from '@/components/LocalTimestamp';
import { RotateSecretButton } from './rotate-secret-button';
import { PageHeader, TableWrap } from '@democracycraft/design-system';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Webhook deliveries', description: 'Delivery health for one of your {server} webhooks.', path: '/me/developer/webhooks' });
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
  const gate = meGate(viewer, 'webhooks.read');
  if (!gate.ok) return <MeGateView kind={gate.kind} title="Webhook deliveries" />;
  if (!Number.isInteger(id) || id <= 0) notFound();

  const hook = await findForOwner(id, gate.uuid);
  if (!hook) notFound();
  await auditView(viewer, { method: 'GET', path: '/me/developer/webhooks/[id]', targetType: 'player', targetId: `webhook:${id}` });

  const deliveries = await listRecentDeliveries(id, gate.uuid, 50);

  return (
    <>
      <PageHeader eyebrow={<Link href="/me/developer/webhooks" prefetch={false}>Webhooks</Link>} title="Webhook deliveries" subtitle={<span className="mono">{hook.url}</span>} />

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
        <TableWrap label="Recent deliveries">
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
        </TableWrap>
      </div>
    </>
  );
}
