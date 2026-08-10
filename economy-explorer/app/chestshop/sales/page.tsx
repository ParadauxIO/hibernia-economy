import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import Link from 'next/link';
import type { Route } from 'next';
import { z } from 'zod';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { PrivacyGate } from '@/components/PrivacyGate';
import { listSalesFeed, countSalesFeed } from '@/lib/sql/market';
import { memo } from '@/lib/cache';
import { fmtAmtFull, fmtN, fmtTs } from '@/lib/format';
import { Player } from '@/components/Player';
import { Pagination } from '@/components/Pagination';
import { Toolbar } from '@/components/Toolbar';
import { SectionTabs } from '@/components/SectionTabs';
import { CsvButton } from '@/components/CsvButton';
import { JsonButton } from '@/components/JsonButton';

const SP_SCHEMA = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(200).default(50),
  q: z.string().trim().min(1).optional(),
  customer: z
    .string()
    .trim()
    .regex(/^[0-9a-fA-F-]{32,36}$/)
    .optional(),
});

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: "Sales", description: "Recent ChestShop sales on {server}.", path: "/chestshop/sales" });
}

export default async function SalesFeedPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const viewer = await getViewer();
  if (viewer.anon) {
    return (
      <PrivacyGate
        kind="login"
        title="ChestShop sales feed"
        hint="Sign in with admin or government access to view trade-by-trade detail with customer attribution."
      />
    );
  }
  if (viewer.role !== 'admin' && viewer.role !== 'government') {
    return <PrivacyGate kind="private" title="Government / admin only" />;
  }

  const sp = SP_SCHEMA.parse(flattenSearchParams(await searchParams));

  // Privileged read: staff browsing trade-by-trade detail with customer
  // attribution. When filtered to one customer, that player is the target.
  await auditView(viewer, {
    path: '/chestshop/sales',
    targetType: sp.customer ? 'player' : 'chestshop',
    targetId: sp.customer ?? null,
  });

  // Cache only the default feed (no customer/search filter) — it's the same
  // global data for every staff viewer. Filtered/per-customer reads stay
  // uncached. The gate + audit above always run per request.
  const fetchSales = () =>
    Promise.all([
      listSalesFeed({
        customerUuid: sp.customer ?? null,
        search: sp.q ?? null,
        limit: sp.limit,
        offset: (sp.page - 1) * sp.limit,
      }),
      countSalesFeed({ customerUuid: sp.customer ?? null, search: sp.q ?? null }),
    ]);
  const [rows, total] =
    !sp.customer && !sp.q
      ? await memo(`sales-default:${sp.limit}:${sp.page}`, 60_000, fetchSales)
      : await fetchSales();
  const totalPages = Math.max(1, Math.ceil(total / sp.limit));

  return (
    <>
      <div className="page-heading">
        <h1>Sales feed</h1>
        <span className="sub">{fmtN(total)} trades · admin/government view</span>
        <SectionTabs />
      </div>

      <Toolbar
        searchPlaceholder="Search item, material, shop, firm…"
        right={
          <span className="toolbar-export">
            <CsvButton
              filename={`sales-feed-page-${sp.page}.csv`}
              headers={['When', 'Direction', 'Quantity', 'Unit price', 'Total', 'Item key', 'Item name', 'Seller', 'Customer', 'Customer UUID', 'World', 'X', 'Y', 'Z']}
              rows={rows.map((s) => [
                s.occurred_at?.toISOString() ?? '',
                s.direction,
                s.quantity,
                s.unit_price,
                s.total_price,
                s.item_key,
                s.item_name ?? '',
                s.owner_name ?? (s.admin_shop ? 'Admin shop' : ''),
                s.customer_name ?? '',
                s.customer_uuid ?? '',
                s.world ?? '',
                s.sign_x,
                s.sign_y,
                s.sign_z,
              ])}
            />
            <JsonButton filename={`sales-feed-page-${sp.page}.json`} data={rows} />
          </span>
        }
      />

      {sp.customer && (
        <div className="card" style={{ padding: '8px 12px', marginBottom: 12 }}>
          Filtered to customer{' '}
          <span className="mono">{sp.customer}</span>{' '}
          <Link href="/chestshop/sales" className="rowlink" prefetch={false}>(clear)</Link>
        </div>
      )}

      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th className="ts">When</th>
              <th>Direction</th>
              <th className="amount">Qty</th>
              <th className="amount">Unit</th>
              <th className="amount">Total</th>
              <th>Item</th>
              <th>Seller</th>
              <th>Customer</th>
              <th>Location</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr><td colSpan={9} className="empty">No sales match.</td></tr>
            )}
            {rows.map((s) => (
              <tr key={s.sale_id}>
                <td className="ts">{fmtTs(s.occurred_at)}</td>
                <td>
                  <span className={`badge ${s.direction === 'BUY' ? 'badge-active' : 'badge-archived'}`}>
                    {s.direction}
                  </span>
                </td>
                <td className="amount mono">{fmtN(s.quantity)}</td>
                <td className="amount neutral">{fmtAmtFull(s.unit_price)}</td>
                <td className="amount neutral">{fmtAmtFull(s.total_price)}</td>
                <td>
                  <Link href={`/chestshop/items/${encodeURIComponent(s.item_key)}` as Route} className="rowlink" prefetch={false}>
                    {s.item_name ?? s.item_key}
                  </Link>
                </td>
                <td>{s.owner_name ?? (s.admin_shop ? 'Admin shop' : '—')}</td>
                <td>
                  {s.customer_uuid ? (
                    <Link
                      href={`/chestshop/sales?customer=${s.customer_uuid}` as Route}
                      className="rowlink"
                      prefetch={false}
                    >
                      <Player name={s.customer_name} uuid={s.customer_uuid} link={false} />
                    </Link>
                  ) : (
                    <Player name={s.customer_name} uuid={s.customer_uuid} />
                  )}
                </td>
                <td>
                  <span className="mono small" style={{ color: 'var(--fg-muted)' }}>
                    {s.world ?? '—'}{s.sign_x !== null ? ` ${s.sign_x},${s.sign_y},${s.sign_z}` : ''}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <Pagination
        page={sp.page}
        totalPages={totalPages}
        totalItems={total}
        basePath="/chestshop/sales"
        searchParams={{ q: sp.q, customer: sp.customer, limit: String(sp.limit) }}
      />
    </>
  );
}

