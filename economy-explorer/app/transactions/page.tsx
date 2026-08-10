import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import Link from 'next/link';
import type { Route } from 'next';
import { z } from 'zod';
import { listTransactions, countTransactions, type SortDir } from '@/lib/sql/ledger';
import { fmtN, fmtTs } from '@/lib/format';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { Pagination } from '@/components/Pagination';
import { Toolbar, FilterSelect, FilterInput } from '@/components/Toolbar';
import { Player } from '@/components/Player';
import { PrivacyGate } from '@/components/PrivacyGate';
import { AdminNav } from '@/components/AdminNav';
import { CsvButton } from '@/components/CsvButton';
import { JsonButton } from '@/components/JsonButton';

const SP_SCHEMA = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(200).default(50),
  q: z.string().trim().min(1).optional(),
  pluginSystem: z.string().trim().min(1).optional(),
  dateFrom: z.string().trim().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
  dateTo: z.string().trim().regex(/^\d{4}-\d{2}-\d{2}$/).optional(),
  minAmount: z.coerce.number().nonnegative().optional(),
  maxAmount: z.coerce.number().nonnegative().optional(),
  accountId: z.coerce.number().int().positive().optional(),
  sort: z.enum(['default', 'txnId', 'settlement']).default('default'),
  dir: z.enum(['ASC', 'DESC', 'asc', 'desc']).default('DESC'),
});

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: "Transactions", description: "Every transfer recorded on the {server} double-entry ledger.", path: "/transactions" });
}

export default async function TransactionsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const viewer = await getViewer();
  if (viewer.anon) {
    return (
      <PrivacyGate
        kind="login"
        title="Transactions firehose is private"
        hint="Sign in with admin access to view the whole-economy transaction list."
      />
    );
  }
  if (viewer.role !== 'admin') {
    return <PrivacyGate kind="private" title="Admin only" hint="Only admins can view the whole-economy transactions list." />;
  }

  const raw = await searchParams;
  const sp = SP_SCHEMA.safeParse(flattenSearchParams(raw)).data ?? SP_SCHEMA.parse({});
  const dir = sp.dir.toUpperCase() as SortDir;

  // Privileged read: admin browsing the whole-economy transaction firehose.
  await auditView(viewer, { path: '/transactions', targetType: 'global', targetId: sp.q ?? null });

  const filters = {
    q: sp.q ?? null,
    pluginSystem: sp.pluginSystem ?? null,
    dateFrom: sp.dateFrom ?? null,
    dateTo: sp.dateTo ?? null,
    minAmount: sp.minAmount ?? null,
    maxAmount: sp.maxAmount ?? null,
    accountId: sp.accountId ?? null,
  };
  const [rows, total] = await Promise.all([
    listTransactions({ limit: sp.limit, offset: (sp.page - 1) * sp.limit, sort: sp.sort, dir, ...filters }),
    countTransactions(filters),
  ]);
  const totalPages = Math.max(1, Math.ceil(total / sp.limit));

  return (
    <>
      <AdminNav />
      <div className="page-heading">
        <h1>Transactions</h1>
        <span className="sub">{fmtN(total)} total</span>
      </div>

      <Toolbar
        searchPlaceholder="Search by message or initiator…"
        filters={
          <>
            <FilterSelect
              paramKey="pluginSystem"
              ariaLabel="Filter by source"
              options={[
                { value: '', label: 'All sources' },
                { value: 'treasury', label: 'treasury' },
                { value: 'rest-api', label: 'rest-api' },
                { value: 'business-rian', label: 'business-rian' },
                { value: 'chestshop-3', label: 'chestshop-3' },
                { value: 'realty', label: 'realty' },
              ]}
            />
            <FilterInput paramKey="accountId" ariaLabel="Filter by account id" type="number" placeholder="Account #" width={110} />
            <FilterInput paramKey="dateFrom" ariaLabel="From date" type="date" width={140} />
            <FilterInput paramKey="dateTo" ariaLabel="To date" type="date" width={140} />
            <FilterInput paramKey="minAmount" ariaLabel="Min amount" type="number" placeholder="Min $" width={90} />
            <FilterInput paramKey="maxAmount" ariaLabel="Max amount" type="number" placeholder="Max $" width={90} />
          </>
        }
        right={
          <span className="toolbar-export">
            <CsvButton
              filename={`transactions-page-${sp.page}.csv`}
              headers={['Txn ID', 'Message', 'Postings', 'Settled', 'Initiator', 'Source']}
              rows={rows.map((r) => [
                r.txn_id,
                r.message ?? '',
                r.posting_count,
                r.settlement_time?.toISOString() ?? '',
                r.initiator_name ?? r.initiator_uuid ?? '',
                r.plugin_system ?? '',
              ])}
            />
            <JsonButton filename={`transactions-page-${sp.page}.json`} data={rows} />
          </span>
        }
      />

      <div className="table-wrap"><table className="data-table">
        <thead>
          <tr>
            <th>Txn</th>
            <th>Message</th>
            <th className="amount">Postings</th>
            <th className="ts">Settled</th>
            <th>Initiator</th>
            <th>Source</th>
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && (
            <tr>
              <td colSpan={6} className="empty">No transactions match.</td>
            </tr>
          )}
          {rows.map((r) => (
            <tr key={r.txn_id}>
              <td>
                <Link href={`/transactions/${r.txn_id}` as Route} className="mono rowlink" prefetch={false}>
                  #{r.txn_id}
                </Link>
              </td>
              <td>{r.message}</td>
              <td className="amount mono">{fmtN(r.posting_count)}</td>
              <td className="ts">{fmtTs(r.settlement_time)}</td>
              <td><Player name={r.initiator_name} uuid={r.initiator_uuid} /></td>
              <td>
                <span className="mono" style={{ color: 'var(--fg-muted)' }}>{r.plugin_system ?? '—'}</span>
              </td>
            </tr>
          ))}
        </tbody>
      </table></div>

      <Pagination
        page={sp.page}
        totalPages={totalPages}
        totalItems={total}
        basePath="/transactions"
        searchParams={{
          q: sp.q,
          pluginSystem: sp.pluginSystem,
          sort: sp.sort,
          dir: sp.dir,
          limit: String(sp.limit),
        }}
      />
    </>
  );
}

