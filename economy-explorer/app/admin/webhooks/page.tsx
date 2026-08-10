import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import Link from 'next/link';
import type { Route } from 'next';
import { z } from 'zod';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { PrivacyGate } from '@/components/PrivacyGate';
import { listAllWebhooks, countAllWebhooks } from '@/lib/sql/webhook';
import { fmtN, fmtTs } from '@/lib/format';
import { Player } from '@/components/Player';
import { Toolbar, FilterSelect } from '@/components/Toolbar';
import { Pagination } from '@/components/Pagination';
import { CreateAdminWebhookForm } from './create-admin-webhook-form';
import { AdminWebhookRowActions } from './admin-webhook-row-actions';

export const dynamic = 'force-dynamic';

const SP_SCHEMA = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(200).default(50),
  q: z.string().trim().min(1).optional(),
  scope: z.enum(['account', 'firm']).optional(),
  status: z.enum(['active', 'paused', 'disabled']).optional(),
  source: z.enum(['explorer', 'apikey']).optional(),
});

export async function generateMetadata() {
  return buildMetadata({ title: 'Webhooks', description: 'Admin: every transaction-feed webhook on {server}.', path: '/admin/webhooks' });
}

export default async function AdminWebhooksPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const viewer = await getViewer();
  if (viewer.anon) return <PrivacyGate kind="login" title="Admin webhooks" hint="Sign in as admin to manage webhooks." />;
  if (viewer.role !== 'admin') return <PrivacyGate kind="private" title="Admin only" />;
  await auditView(viewer, { path: '/admin/webhooks', targetType: 'global' });

  const sp = SP_SCHEMA.safeParse(flattenSearchParams(await searchParams)).data ?? SP_SCHEMA.parse({});
  const filters = { q: sp.q ?? null, scope: sp.scope ?? null, status: sp.status ?? null, source: sp.source ?? null };
  const [rows, total] = await Promise.all([
    listAllWebhooks(filters, sp.limit, (sp.page - 1) * sp.limit),
    countAllWebhooks(filters),
  ]);
  const totalPages = Math.max(1, Math.ceil(total / sp.limit));

  return (
    <>
      <div className="page-heading">
        <h1>Webhooks</h1>
        <span className="sub">{fmtN(total)} subscription(s) across all accounts</span>
      </div>

      <div className="card">
        <div className="card-title">Create webhook for an account</div>
        <CreateAdminWebhookForm />
        <p className="muted small" style={{ marginTop: 10, marginBottom: 0 }}>
          Registers an account-scoped webhook attributed to the account&apos;s owner (they&apos;ll see it under their own
          webhooks too). The endpoint must be a public <span className="mono">https://</span> URL; the signing secret is
          shown once. A Discord webhook URL is auto-detected and delivered as a rich embed.
        </p>
      </div>

      <div className="card">
        <div className="card-title">All webhooks</div>
        <Toolbar
          searchPlaceholder="Search URL, owner, account/firm name, or id…"
          filters={
            <>
              <FilterSelect
                paramKey="scope"
                ariaLabel="Filter by scope"
                options={[{ value: '', label: 'All scopes' }, { value: 'account', label: 'Account' }, { value: 'firm', label: 'Firm' }]}
              />
              <FilterSelect
                paramKey="status"
                ariaLabel="Filter by status"
                options={[
                  { value: '', label: 'Any status' },
                  { value: 'active', label: 'Active' },
                  { value: 'paused', label: 'Paused' },
                  { value: 'disabled', label: 'Auto-disabled' },
                ]}
              />
              <FilterSelect
                paramKey="source"
                ariaLabel="Filter by source"
                options={[{ value: '', label: 'Any source' }, { value: 'explorer', label: 'Explorer' }, { value: 'apikey', label: 'API key' }]}
              />
            </>
          }
        />
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>#</th>
                <th>Endpoint</th>
                <th>Owner</th>
                <th>Scope</th>
                <th>Status</th>
                <th className="amount">Fails</th>
                <th>Source</th>
                <th className="ts">Created</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.length === 0 && <tr><td colSpan={9} className="empty">No webhooks match.</td></tr>}
              {rows.map((h) => (
                <tr key={h.id}>
                  <td className="mono">{h.id}</td>
                  <td>
                    <span className="mono">{h.url}</span>
                    {h.isDiscord && <span className="badge badge-active" style={{ marginLeft: 6 }} title="Deliveries sent as a rich Discord embed">Discord</span>}
                  </td>
                  <td><Player name={h.ownerName} uuid={h.ownerUuid} /></td>
                  <td>
                    {h.scope === 'firm' ? (
                      h.firmId && h.firmName ? (
                        <Link href={`/firms/${encodeURIComponent(h.firmName)}` as Route} className="rowlink" prefetch={false}>Firm: {h.firmName}</Link>
                      ) : <span className="badge">firm #{h.firmId}</span>
                    ) : h.accountId ? (
                      <Link href={`/accounts/${h.accountId}` as Route} className="rowlink" prefetch={false}>Acct: {h.accountName ?? `#${h.accountId}`}</Link>
                    ) : <span className="muted">—</span>}
                  </td>
                  <td>
                    {h.active
                      ? <span className="badge badge-active">active</span>
                      : <span className="badge badge-archived">{h.disabledAt ? 'auto-disabled' : 'paused'}</span>}
                  </td>
                  <td className="amount mono">{fmtN(h.consecutiveFailures)}</td>
                  <td><span className="badge">{h.viaApiKey ? 'API key' : 'explorer'}</span></td>
                  <td className="ts">{fmtTs(h.createdAt)}</td>
                  <td style={{ whiteSpace: 'nowrap' }}><AdminWebhookRowActions id={h.id} active={h.active} url={h.url} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <Pagination
          page={sp.page}
          totalPages={totalPages}
          totalItems={total}
          basePath="/admin/webhooks"
          searchParams={{ q: sp.q, scope: sp.scope, status: sp.status, source: sp.source, limit: String(sp.limit) }}
        />
      </div>
    </>
  );
}

