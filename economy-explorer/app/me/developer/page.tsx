import Link from 'next/link';
import type { Route } from 'next';
import { PageHeader, Section, TableWrap, Tag, When } from '@democracycraft/design-system';
import { EntityLink } from '@democracycraft/design-system/next';
import { getViewer } from '@/lib/auth/viewer';
import { hasPermission } from '@/lib/auth/policy';
import { accountLabel } from '@/lib/format';
import { buildMetadata } from '@/lib/metadata';
import { meGate } from '@/lib/services/me';
import { selectApiKeysForOwner } from '@/lib/sql/me';
import { listForOwner } from '@/lib/sql/webhook';
import { MeGateView } from '../_components/MeGateView';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Webhooks and API', description: 'Your {server} API keys and webhooks.', path: '/me/developer' });
}

const KEY_TYPE: Record<string, string> = { PERSONAL: 'Personal', BUSINESS: 'Business', GOVERNMENT: 'Government', SERVICE: 'Service' };

/** Read-only list of the player's API keys (never the tokens) and a summary of their webhooks (spec §8.7). Two queries. */
export default async function DeveloperPage() {
  const viewer = await getViewer();
  const gate = meGate(viewer);
  if (!gate.ok) return <MeGateView kind={gate.kind} title="Webhooks and API" />;
  const canWebhooks = hasPermission(viewer, 'webhooks.read');
  const [keys, hooks] = await Promise.all([selectApiKeysForOwner(gate.uuid), canWebhooks ? listForOwner(gate.uuid) : Promise.resolve([])]);
  const now = new Date();
  const multiplier = keys.find((k) => k.rate_multiplier !== null)?.rate_multiplier ?? null;

  return <>
    <PageHeader title="Webhooks and API" subtitle="Build on the economy: API keys you issued in game, and webhooks that notify you when money moves." />

    <Section title="API keys" description={<>Keys are issued in game and their tokens are shown only once, so they can’t be shown here. <Link href="/docs/guides/api" prefetch={false}>Using the economy API</Link></>}>
      {keys.length === 0 ? <ol className="me-steps">
        <li>In game, run <code className="me-code">/treasuryapi personal issue</code> for your personal account, or <code className="me-code">/treasuryapi business issue &lt;firm&gt;</code> for a firm you own.</li>
        <li>Copy the token from chat. It is shown once and never stored.</li>
        <li>Send it as <code className="me-code">Authorization: Bearer &lt;token&gt;</code>. Keys last 180 days; rotate them through the API before then.</li>
      </ol> : <>
        <p className="me-note">
          Rate limit: {multiplier ? <>your keys share {Number(multiplier)}× the default allowance (set by an administrator)</> : 'the default allowance'}, shared across all your keys and counted per route. See the API guide for the figures.
        </p>
        <TableWrap label="Your API keys">
          <table className="data-table">
            <thead><tr>
              <th scope="col">Key</th>
              <th scope="col">Scope</th>
              <th scope="col">Issued</th>
              <th scope="col">Expires</th>
              <th scope="col">Status</th>
            </tr></thead>
            <tbody>{keys.map((k) => {
              const expired = k.expires_at < now;
              return <tr key={k.key_id}>
                <th scope="row">#{k.key_id} <span className="dt-sub">{KEY_TYPE[k.key_type] ?? k.key_type}</span></th>
                <td>{k.account_id !== null
                  ? <EntityLink kind="account" href={`/accounts/${k.account_id}`} name={accountLabel({ account_id: k.account_id, display_name: k.account_name, owner_name: k.account_owner_name })} id={k.account_id} />
                  : k.firm_id !== null ? <>{k.firm_name ?? `Firm #${k.firm_id}`} <span className="dt-sub">· whole firm</span></> : '—'}</td>
                <td><When value={k.issued_at} /></td>
                <td><When value={k.expires_at} /></td>
                <td>{k.revoked ? <Tag>Revoked</Tag> : expired ? <Tag tone="warn">Expired</Tag> : <Tag tone="pos">Active</Tag>}</td>
              </tr>;
            })}</tbody>
          </table>
        </TableWrap>
        <p className="me-note">Revoke or reissue a key in game: <code className="me-code">/treasuryapi personal revoke &lt;keyId&gt;</code>.</p>
      </>}
    </Section>

    <Section title="Webhooks" divider description="A signed POST to your server for each settled transaction on your account or firm."
      actions={canWebhooks ? <Link href={'/me/developer/webhooks' as Route} className="btn btn-quiet" prefetch={false}>Manage webhooks</Link> : undefined}>
      {!canWebhooks ? <p className="me-empty">Your roles don’t include webhooks.</p>
        : <p className="me-note">{hooks.length === 0 ? 'You have no webhooks yet.' : `${hooks.length} registered, ${hooks.filter((h) => h.active).length} active.`} <Link href="/docs/guides/webhooks" prefetch={false}>Webhooks guide</Link></p>}
    </Section>
  </>;
}
