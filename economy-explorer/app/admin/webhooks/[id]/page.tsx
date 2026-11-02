import Link from 'next/link';
import { notFound } from 'next/navigation';
import { PageHeader, Section, TableWrap, When } from '@democracycraft/design-system';
import { getViewer } from '@/lib/auth/viewer';
import { hasPermission } from '@/lib/auth/policy';
import { getAdminWebhookDetail } from '@/lib/services/admin';
import { treasuryAdminConfigured } from '@/lib/treasury';
import { AdminGate } from '../../_components/AdminGate';
import { AdminWebhookRowActions } from '../admin-webhook-row-actions';
import { WebhookEditor } from './webhook-editor';
export const dynamic = 'force-dynamic';
export default async function AdminWebhookDetail({ params }: { params: Promise<{ id: string }> }) {
  const viewer = await getViewer();
  if (!hasPermission(viewer, 'webhooks.read.all')) return <AdminGate viewer={viewer} title="Webhook administration access required" permission="webhooks.read.all" />;
  const id = Number((await params).id);
  if (!Number.isSafeInteger(id) || id <= 0) notFound();
  const detail = await getAdminWebhookDetail(viewer, id);
  if (!detail) notFound();
  const { hook, deliveries } = detail;
  return <>
    <Link href="/admin/webhooks">Webhooks</Link>
    <PageHeader title={`Webhook #${id}`} subtitle={`${hook.ownerName ?? hook.ownerUuid ?? 'Unknown owner'} · ${hook.active ? 'Active' : hook.disabledAt ? 'Auto-disabled' : 'Paused'} · ${hook.consecutiveFailures} consecutive failures`} />
    <Section title="Subscription">
      <AdminWebhookRowActions id={id} active={hook.active} url={hook.url} />
      <WebhookEditor id={id} url={hook.url} disabled={!treasuryAdminConfigured()} />
    </Section>
    <Section title="Recent deliveries" description="Latest 50 attempts. Enabling a subscription does not replay exhausted or skipped deliveries.">
      <TableWrap label="Recent deliveries"><table className="data-table">
        <thead><tr><th>Delivery</th><th>Transaction</th><th>Account</th><th>Status</th><th>Attempts</th><th>HTTP</th><th>Last error</th><th>Next attempt</th><th>Created</th></tr></thead>
        <tbody>{deliveries.map(d => <tr key={d.deliveryId}>
          <td>#{d.deliveryId}</td><td>#{d.txnId}</td><td>#{d.accountId}</td><td>{d.status}</td><td>{d.attempts}</td><td>{d.httpStatus ?? '—'}</td><td>{d.lastError ?? '—'}</td><td>{d.status === 'PENDING' ? <When value={d.nextAttemptAt} /> : '—'}</td><td><When value={d.createdAt} /></td>
        </tr>)}{!deliveries.length && <tr><td colSpan={9}>No deliveries yet.</td></tr>}</tbody>
      </table></TableWrap>
    </Section>
  </>;
}
