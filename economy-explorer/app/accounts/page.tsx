import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import Link from 'next/link';
import type { Route } from 'next';
import { z } from 'zod';
import { listAccounts, countAccounts, type SortColumn, type SortDir } from '@/lib/sql/ledger';
import { memo } from '@/lib/cache';
import { accountLabel, fmtAmtFull, fmtTs, shortenUuid, looksLikeUuid } from '@/lib/format';
import { Toolbar, FilterSelect } from '@/components/Toolbar';
import { Pagination } from '@/components/Pagination';
import { CsvButton } from '@/components/CsvButton';
import { JsonButton } from '@/components/JsonButton';

const SP_SCHEMA = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(200).default(50),
  q: z.string().trim().min(1).optional(),
  type: z.enum(['PERSONAL', 'BUSINESS', 'GOVERNMENT', 'SYSTEM']).optional(),
  sort: z.enum(['account_id', 'balance', 'name', 'type', 'created']).default('account_id'),
  dir: z.enum(['ASC', 'DESC', 'asc', 'desc']).default('DESC'),
});

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: "Accounts", description: "Browse every account on the {server} economy ledger, with balances and recent activity.", path: "/accounts" });
}

export default async function AccountsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const raw = await searchParams;
  const parsed = SP_SCHEMA.safeParse(flattenSearchParams(raw));
  const sp = parsed.success ? parsed.data : SP_SCHEMA.parse({});
  const dir: SortDir = sp.dir.toUpperCase() as SortDir;
  // Inactive/archived accounts are hidden entirely — active only.
  const archived = 0;

  // Cache the default (unsearched) browse; search queries vary per request.
  const fetchAccounts = () =>
    Promise.all([
      listAccounts({
        limit: sp.limit,
        offset: (sp.page - 1) * sp.limit,
        type: sp.type ?? null,
        archived,
        q: sp.q ?? null,
        sort: sp.sort as SortColumn,
        dir,
      }),
      countAccounts({ type: sp.type ?? null, archived, q: sp.q ?? null }),
    ]);
  const [rows, total] = sp.q
    ? await fetchAccounts()
    : await memo(`accounts-list:${sp.type ?? 'all'}:${sp.sort}:${dir}:${sp.page}:${sp.limit}`, 60_000, fetchAccounts);

  const totalPages = Math.max(1, Math.ceil(total / sp.limit));

  return (
    <>
      <div className="page-heading">
        <h1>Accounts</h1>
        <span className="sub">{total.toLocaleString()} total</span>
      </div>

      <Toolbar
        searchPlaceholder="Search by name, display name, or UUID…"
        filters={
          <FilterSelect
            paramKey="type"
            ariaLabel="Filter by account type"
            options={[
              { value: '', label: 'All types' },
              { value: 'PERSONAL', label: 'Personal' },
              { value: 'BUSINESS', label: 'Business' },
              { value: 'GOVERNMENT', label: 'Government' },
              { value: 'SYSTEM', label: 'System' },
            ]}
          />
        }
        right={
          <span className="toolbar-export">
            <CsvButton
              filename={`accounts-page-${sp.page}.csv`}
              headers={['Account ID', 'Type', 'Name', 'Owner', 'Owner UUID', 'Balance', 'Created']}
              rows={rows.map((a) => [
                a.account_id,
                a.account_type,
                accountLabel(a),
                a.owner_name ?? '',
                a.owner_uuid ?? '',
                a.balance,
                a.created_at.toISOString(),
              ])}
            />
            <JsonButton filename={`accounts-page-${sp.page}.json`} data={rows} />
          </span>
        }
      />

      <div className="table-wrap"><table className="data-table">
        <thead>
          <tr>
            <th>{sortHeader('Name', 'name', sp.sort, dir, raw)}</th>
            <th>{sortHeader('Type', 'type', sp.sort, dir, raw)}</th>
            <th className="amount">{sortHeader('Balance', 'balance', sp.sort, dir, raw)}</th>
            <th className="ts">{sortHeader('Created', 'created', sp.sort, dir, raw)}</th>
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && (
            <tr>
              <td colSpan={4} className="empty">
                No accounts match. Try clearing the search or filters.
              </td>
            </tr>
          )}
          {rows.map((a) => (
            <tr key={a.account_id}>
              <td>
                <Link href={`/accounts/${a.account_id}` as Route} className="rowlink" prefetch={false}>
                  <span style={{ fontWeight: 500 }}>{accountLabel(a)}</span>
                  <span className="mono muted small">{` #${a.account_id}`}</span>
                  {a.display_name && !looksLikeUuid(a.display_name) && a.owner_name && a.display_name !== a.owner_name && (
                    <span className="muted small">{` owner: ${a.owner_name}`}</span>
                  )}
                  {looksLikeUuid(a.display_name) && !a.owner_name && a.owner_uuid && (
                    <span className="mono muted small" title={a.owner_uuid}>{` ${shortenUuid(a.owner_uuid)}`}</span>
                  )}
                </Link>
              </td>
              <td>
                <span className={`badge badge-${a.account_type}`}>{a.account_type}</span>
              </td>
              <td className="amount neutral">{fmtAmtFull(a.balance)}</td>
              <td className="ts">{fmtTs(a.created_at)}</td>
            </tr>
          ))}
        </tbody>
      </table></div>

      <Pagination
        page={sp.page}
        totalPages={totalPages}
        totalItems={total}
        basePath="/accounts"
        searchParams={{
          q: sp.q,
          type: sp.type,
          sort: sp.sort,
          dir: sp.dir,
          limit: String(sp.limit),
        }}
      />
    </>
  );
}


function sortHeader(
  label: string,
  col: SortColumn,
  activeSort: string,
  activeDir: SortDir,
  raw: Record<string, string | string[] | undefined>,
) {
  const isActive = activeSort === col;
  const nextDir: SortDir = isActive && activeDir === 'DESC' ? 'ASC' : 'DESC';
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(raw)) {
    if (Array.isArray(v)) {
      if (v[0]) sp.set(k, v[0]);
    } else if (v !== undefined) {
      sp.set(k, v);
    }
  }
  sp.set('sort', col);
  sp.set('dir', nextDir);
  sp.delete('page');
  return (
    <Link href={`/accounts?${sp.toString()}` as Route} className={isActive ? 'sortlink active' : 'sortlink'}>
      {label}
      {isActive ? (activeDir === 'DESC' ? ' ↓' : ' ↑') : ''}
    </Link>
  );
}
