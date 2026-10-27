import { hasPermission } from '@/lib/auth/policy';
import { buildMetadata } from '@/lib/metadata';
import Link from 'next/link';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { meGate } from '@/lib/services/me';
import { MeGateView } from '../../_components/MeGateView';
import { findAccountsForPlayer } from '@/lib/sql/me';
import { listForOwner, listFinanceFirms } from '@/lib/sql/webhook';
import { fmtN } from '@/lib/format';
import { LocalTimestamp } from '@/components/LocalTimestamp';
import { CreateWebhookForm } from './create-webhook-form';
import { WebhookRowActions } from './webhook-row-actions';
import { PageHeader, TableWrap } from '@democracycraft/design-system';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({
    title: 'Webhooks',
    description: 'Manage your {server} transaction-feed webhooks — get a signed POST when your account or firm moves money.',
    path: '/me/developer/webhooks',
  });
}

export default async function WebhooksPage() {
  const viewer = await getViewer();
  const gate = meGate(viewer, 'webhooks.read');
  if (!gate.ok) return <MeGateView kind={gate.kind} title="Webhooks" />;
  await auditView(viewer, { method: 'GET', path: '/me/developer/webhooks', targetType: 'player', targetId: gate.uuid });

  const uuid = gate.uuid;
  const [accounts, firms, hooks] = await Promise.all([
    findAccountsForPlayer(uuid),
    listFinanceFirms(uuid),
    listForOwner(uuid),
  ]);
  const personal = accounts.find((a) => a.account_type === 'PERSONAL' && a.owner_uuid === uuid) ?? null;

  return (
    <>
      <PageHeader eyebrow={<Link href="/me/developer" prefetch={false}>Developer</Link>} title="Webhooks"
        subtitle={`${fmtN(hooks.length)} registered. A signed POST for each settled transaction touching the scope.`} />

      <div className="card">
        <div className="card-title">New webhook</div>
        {hasPermission(viewer, 'webhooks.manage') && (personal || firms.length > 0) ? (
          <CreateWebhookForm
            hasPersonal={!!personal}
            firms={firms.map((f) => ({ firmId: f.firmId, displayName: f.displayName }))}
          />
        ) : (
          <p className="muted small" style={{ margin: 0 }}>
            {!hasPermission(viewer, 'webhooks.manage')
              ? 'Your roles allow viewing webhooks but do not allow managing them.'
              : 'You have no personal account and no firm with financial access, so there is nothing to scope a webhook to.'}
          </p>
        )}
        <p className="muted small" style={{ marginTop: 10, marginBottom: 0 }}>
          The endpoint must be a public <span className="mono">https://</span> URL. Each delivery is signed with
          {' '}<span className="mono">X-Treasury-Signature: sha256=…</span> over the request body using the secret shown once at
          creation. After repeated failures a webhook auto-disables; re-enable it once your endpoint recovers.
        </p>
      </div>

      <div className="card">
        <div className="card-title">Your webhooks</div>
        <TableWrap label="Your webhooks">
          <table className="data-table">
            <thead>
              <tr>
                <th>Endpoint</th>
                <th>Scope</th>
                <th>Status</th>
                <th className="amount">Failures</th>
                <th className="ts">Created</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {hooks.length === 0 && <tr><td colSpan={6} className="empty">No webhooks yet.</td></tr>}
              {hooks.map((h) => (
                <tr key={h.id}>
                  <td>
                    <Link href={`/me/developer/webhooks/${h.id}`} className="rowlink" prefetch={false}>
                      <span className="mono">{h.url}</span>
                    </Link>
                    {h.isDiscord && <span className="badge badge-active" style={{ marginLeft: 6 }} title="Deliveries are sent as a rich Discord embed">Discord</span>}
                    {h.viaApiKey && <span className="badge" style={{ marginLeft: 6 }} title="Created via an in-game API key">API key</span>}
                  </td>
                  <td>
                    <span className="badge">{h.scope === 'firm' ? `firm #${h.firmId}` : `account #${h.accountId}`}</span>
                  </td>
                  <td>
                    {h.active
                      ? <span className="badge badge-active">active</span>
                      : <span className="badge badge-archived">{h.disabledAt ? 'auto-disabled' : 'paused'}</span>}
                  </td>
                  <td className="amount mono">{fmtN(h.consecutiveFailures)}</td>
                  <td className="ts"><LocalTimestamp value={h.createdAt} /></td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    <Link href={`/me/developer/webhooks/${h.id}`} className="btn" prefetch={false}>Deliveries</Link>{' '}
                    {hasPermission(viewer, 'webhooks.manage') && <WebhookRowActions id={h.id} active={h.active} url={h.url} />}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </TableWrap>
      </div>
    </>
  );
}
