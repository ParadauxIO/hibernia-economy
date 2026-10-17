import { hasPermission } from '@/lib/auth/policy';
import { buildMetadata } from '@/lib/metadata';
import Link from 'next/link';
import type { Route } from 'next';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { PrivacyGate } from '@/components/PrivacyGate';
import { findAccountsForPlayer } from '@/lib/sql/me';
import { listForOwner, listFinanceFirms } from '@/lib/sql/webhook';
import { fmtN } from '@/lib/format';
import { LocalTimestamp } from '@/components/LocalTimestamp';
import { CreateWebhookForm } from './create-webhook-form';
import { WebhookRowActions } from './webhook-row-actions';
import { TableWrap } from '@/components/TableWrap';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({
    title: 'Webhooks',
    description: 'Manage your {server} transaction-feed webhooks — get a signed POST when your account or firm moves money.',
    path: '/me/webhooks',
  });
}

export default async function WebhooksPage() {
  const viewer = await getViewer();
  if (!viewer.anon && !hasPermission(viewer, 'webhooks.read')) return <PrivacyGate kind="private" title="Permission required" />;
  if (viewer.anon) {
    return (
      <PrivacyGate
        kind="login"
        title="Webhooks"
        hint="Sign in to register webhooks for your own account and the firms you manage."
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
  await auditView(viewer, { method: 'GET', path: '/me/webhooks', targetType: 'player', targetId: viewer.minecraftUuid });

  const uuid = viewer.minecraftUuid;
  const [accounts, firms, hooks] = await Promise.all([
    findAccountsForPlayer(uuid),
    listFinanceFirms(uuid),
    listForOwner(uuid),
  ]);
  const personal = accounts.find((a) => a.account_type === 'PERSONAL' && a.owner_uuid === uuid) ?? null;

  return (
    <>
      <div className="page-heading">
        <h1>Webhooks</h1>
        <span className="sub">
          {fmtN(hooks.length)} registered · a signed POST per settled transaction touching the scope
        </span>
        <Link href="/me" className="btn" prefetch={false} style={{ marginLeft: 'auto' }}>
          ← My data
        </Link>
      </div>

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
                    <Link href={`/me/webhooks/${h.id}` as Route} className="rowlink" prefetch={false}>
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
                    <Link href={`/me/webhooks/${h.id}` as Route} className="btn" prefetch={false}>Deliveries</Link>{' '}
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
