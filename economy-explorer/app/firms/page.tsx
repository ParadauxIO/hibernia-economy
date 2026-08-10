import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import Link from 'next/link';
import type { Route } from 'next';
import { z } from 'zod';
import { listFirms, countFirms, type FirmSortColumn, type SortDir } from '@/lib/sql/ledger';
import { getFirmStats } from '@/lib/sql/firm';
import { memo } from '@/lib/cache';
import { fmtAmt, fmtAmtFull, fmtN, fmtTs } from '@/lib/format';
import { Toolbar, FilterSelect } from '@/components/Toolbar';
import { Pagination } from '@/components/Pagination';
import { CsvButton } from '@/components/CsvButton';
import { JsonButton } from '@/components/JsonButton';

const SP_SCHEMA = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(200).default(50),
  q: z.string().trim().min(1).optional(),
  archived: z.enum(['true', 'false']).optional(),
  sort: z.enum(['balance', 'name', 'employees', 'created']).default('balance'),
  dir: z.enum(['ASC', 'DESC', 'asc', 'desc']).default('DESC'),
});

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: "Firms", description: "Registered businesses on {server}, with balances and ownership.", path: "/firms" });
}

export default async function FirmsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const raw = await searchParams;
  const sp = SP_SCHEMA.safeParse(flattenSearchParams(raw)).data ?? SP_SCHEMA.parse({});
  const dir = sp.dir.toUpperCase() as SortDir;
  const archived = sp.archived === undefined ? null : sp.archived === 'true' ? 1 : 0;

  // getFirmStats is tenant-global; the list's default (unsearched) browse is
  // cacheable. Search queries vary per request and stay uncached.
  const fetchList = () =>
    Promise.all([
      listFirms({
        limit: sp.limit,
        offset: (sp.page - 1) * sp.limit,
        q: sp.q ?? null,
        archived,
        sort: sp.sort as FirmSortColumn,
        dir,
      }),
      countFirms({ q: sp.q ?? null, archived }),
    ]);
  const [[rows, total], stats] = await Promise.all([
    sp.q
      ? fetchList()
      : memo(`firms-list:${archived ?? 'all'}:${sp.sort}:${dir}:${sp.page}:${sp.limit}`, 60_000, fetchList),
    memo('firm-stats', 60_000, () => getFirmStats()),
  ]);
  const totalPages = Math.max(1, Math.ceil(total / sp.limit));

  return (
    <>
      <div className="page-heading">
        <h1>Firms</h1>
        <span className="sub">{fmtN(total)} total</span>
      </div>

      <div className="kpi-grid">
        <div className="kpi">
          <div className="kpi-label">Business wealth</div>
          <div className="kpi-value">{fmtAmt(stats.businessWealth)}</div>
          <div className="kpi-meta">held across business accounts</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">New this month</div>
          <div className="kpi-value">{fmtN(stats.newThisMonth)}</div>
          <div className="kpi-meta">{fmtN(stats.activeFirms)} active firms</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Employees</div>
          <div className="kpi-value">{fmtN(stats.activeEmployees)}</div>
          <div className="kpi-meta">across all firms</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Avg headcount</div>
          <div className="kpi-value">{stats.avgEmployees.toFixed(1)}</div>
          <div className="kpi-meta">employees per active firm</div>
        </div>
      </div>

      <Toolbar
        searchPlaceholder="Search by firm name or HQ region…"
        filters={
          <FilterSelect
            paramKey="archived"
            ariaLabel="Filter by status"
            options={[
              { value: '', label: 'All status' },
              { value: 'false', label: 'Active' },
              { value: 'true', label: 'Archived' },
            ]}
          />
        }
        right={
          <span className="toolbar-export">
            <CsvButton
              filename={`firms-page-${sp.page}.csv`}
              headers={['Firm ID', 'Name', 'HQ Region', 'Employees', 'Accounts', 'Total Balance', 'Status', 'Founded']}
              rows={rows.map((f) => [
                f.firm_id,
                f.display_name,
                f.hq_region ?? '',
                f.employee_count,
                f.account_count,
                f.total_balance,
                f.archived ? 'Archived' : 'Active',
                f.created_at.toISOString(),
              ])}
            />
            <JsonButton filename={`firms-page-${sp.page}.json`} data={rows} />
          </span>
        }
      />

      <div className="table-wrap"><table className="data-table">
        <thead>
          <tr>
            <th>Firm</th>
            <th>HQ</th>
            <th className="amount">Employees</th>
            <th className="amount">Accounts</th>
            <th className="amount">Total balance</th>
            <th className="ts">Founded</th>
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && (
            <tr>
              <td colSpan={6} className="empty">No firms match.</td>
            </tr>
          )}
          {rows.map((f) => (
            <tr key={f.firm_id}>
              <td>
                <Link href={`/firms/${encodeURIComponent(f.display_name)}` as Route} className="rowlink" prefetch={false}>
                  <span style={{ fontWeight: 500 }}>{f.display_name}</span>
                  <span className="mono muted small">{` #${f.firm_id}`}</span>
                  {f.archived ? <span className="badge badge-archived" style={{ marginLeft: 8 }}>Archived</span> : null}
                </Link>
              </td>
              <td>{f.hq_region ?? <span className="muted">—</span>}</td>
              <td className="amount mono">{fmtN(f.employee_count)}</td>
              <td className="amount mono">{fmtN(f.account_count)}</td>
              <td className="amount neutral">{fmtAmtFull(f.total_balance)}</td>
              <td className="ts">{fmtTs(f.created_at)}</td>
            </tr>
          ))}
        </tbody>
      </table></div>

      <Pagination
        page={sp.page}
        totalPages={totalPages}
        totalItems={total}
        basePath="/firms"
        searchParams={{ q: sp.q, archived: sp.archived, sort: sp.sort, dir: sp.dir, limit: String(sp.limit) }}
      />
    </>
  );
}

