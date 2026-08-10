import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import Link from 'next/link';
import type { Route } from 'next';
import { z } from 'zod';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { listAccounts } from '@/lib/sql/ledger';
import { treasuryAdminConfigured } from '@/lib/treasury';
import { fmtAmt } from '@/lib/format';
import { Toolbar } from '@/components/Toolbar';
import { MoveMoneyPanel } from './move-money-panel';
import { AccountAdminPanel } from './account-admin-panel';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Admin · Accounts', description: 'Administer accounts: move money, rename, change owner, archive.', path: '/admin/accounts' });
}

const SP = z.object({ q: z.string().trim().min(1).optional() });

/** Admin account tool (PAR-219/221): move money between accounts, and per-account
 *  rename / change-owner / archive-unarchive — all via the ledger-authoritative
 *  treasury admin API, audited. */
export default async function AdminAccountsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  if (viewer.anon || viewer.role !== 'admin') return null; // layout gates; defence in depth
  const sp = SP.safeParse(flattenSearchParams(await searchParams)).data ?? {};
  const q = sp.q ?? null;
  await auditView(viewer, { path: '/admin/accounts', targetType: 'global', targetId: q });

  const configured = treasuryAdminConfigured();
  const accounts = q
    ? await listAccounts({ limit: 25, offset: 0, type: null, archived: null, q, sort: 'account_id', dir: 'ASC' })
    : [];

  return (
    <>
      <div className="page-heading">
        <h1>Accounts</h1>
        <span className="sub">move money, rename, change owner, archive — destructive, audited</span>
      </div>

      {!configured && (
        <p className="state-error" style={{ marginBottom: 12 }}>
          Treasury admin API is not configured in this environment (TREASURY_API_BASE_URL / TREASURY_ADMIN_TOKEN).
        </p>
      )}

      <MoveMoneyPanel disabled={!configured} />

      <div className="card" style={{ marginTop: 12 }}>
        <div className="card-title">Find an account</div>
        <Toolbar searchPlaceholder="Search by account id, name, or owner…" />
      </div>

      {q && accounts.length === 0 && <p className="muted" style={{ marginTop: 12 }}>No accounts match “{q}”.</p>}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 12 }}>
        {accounts.map((a) => (
          <div key={a.account_id} className="card" style={{ padding: 14 }}>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, flexWrap: 'wrap' }}>
              <Link href={`/accounts/${a.account_id}` as Route} className="rowlink mono">#{a.account_id}</Link>
              <strong>{a.display_name ?? '—'}</strong>
              <span className={`badge badge-${a.account_type}`}>{a.account_type}</span>
              <span className="muted small">owner {a.owner_name ?? a.owner_uuid ?? '—'}</span>
              <span className="muted small">balance {fmtAmt(a.balance)}</span>
              {a.archived ? <span className="badge" style={{ background: 'var(--bad)', color: '#fff' }}>archived</span> : null}
            </div>
            <AccountAdminPanel accountId={a.account_id} displayName={a.display_name} archived={a.archived === 1} />
          </div>
        ))}
      </div>
    </>
  );
}

