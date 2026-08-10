import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import Link from 'next/link';
import type { Route } from 'next';
import { z } from 'zod';
import { listShops, countShops } from '@/lib/sql/market';
import { memo } from '@/lib/cache';
import { fmtAmtFull, fmtN, fmtTs } from '@/lib/format';
import { Toolbar } from '@/components/Toolbar';
import { Pagination } from '@/components/Pagination';
import { SectionTabs } from '@/components/SectionTabs';
import { CsvButton } from '@/components/CsvButton';
import { JsonButton } from '@/components/JsonButton';
import { ItemIcon } from '@/components/market/ItemIcon';
import { itemIconUrl } from '@/lib/itemIcon';
import { resolveTheme } from '@/lib/theme';
import { serverIdentity, blueMapUrl } from '@/lib/serverIdentity';

const SP_SCHEMA = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(200).default(50),
  q: z.string().trim().min(1).optional(),
  item: z.string().trim().min(1).optional(),
  buyable: z.enum(['true', 'false']).optional(),
  inStock: z.enum(['true', 'false']).optional(),
});

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: "Shops", description: "Every ChestShop on {server}, with stock and pricing.", path: "/chestshop/shops" });
}

export default async function ShopsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const sp = SP_SCHEMA.parse(flattenSearchParams(await searchParams));
  const args = {
    itemKey: sp.item ?? null,
    material: null,
    firmId: null,
    ownerUuid: null,
    buyable: sp.buyable === 'true',
    inStock: sp.inStock === 'true',
    search: sp.q ?? null,
  };
  // Cache only the default (unfiltered) browse — the common heavy path. Filtered/
  // search queries vary per request and stay uncached.
  const fetchShops = () =>
    Promise.all([
      listShops({ ...args, limit: sp.limit, offset: (sp.page - 1) * sp.limit }),
      countShops(args),
    ]);
  const isDefault = !sp.q && !sp.item && !args.buyable && !args.inStock;
  const [[shops, total], theme] = await Promise.all([
    isDefault ? memo(`shops-default:${sp.limit}:${sp.page}`, 60_000, fetchShops) : fetchShops(),
    resolveTheme(),
  ]);
  const totalPages = Math.max(1, Math.ceil(total / sp.limit));
  const mapBase = serverIdentity(theme).map;

  return (
    <>
      <div className="page-heading">
        <h1>Live shops</h1>
        <span className="sub">{fmtN(total)} active</span>
        <SectionTabs />
      </div>

      <Toolbar
        searchPlaceholder="Search items or materials…"
        right={
          <span className="toolbar-export">
            <CsvButton
              filename={`shops-page-${sp.page}.csv`}
              headers={['Shop ID', 'Item key', 'Item name', 'Material', 'Owner', 'Owner UUID', 'Type', 'Buy', 'Sell', 'Stock', 'World', 'X', 'Y', 'Z', 'Last seen']}
              rows={shops.map((s) => [
                s.shop_id,
                s.item_key,
                s.item_name ?? '',
                s.material ?? '',
                s.owner_name ?? (s.admin_shop ? 'Admin shop' : ''),
                s.shop_owner_uuid ?? '',
                s.shop_account_type ?? (s.admin_shop ? 'ADMIN' : ''),
                s.buy_price ?? '',
                s.sell_price ?? '',
                s.admin_shop ? 'unlimited' : (s.current_stock ?? ''),
                s.world ?? '',
                s.sign_x,
                s.sign_y,
                s.sign_z,
                s.last_seen?.toISOString() ?? '',
              ])}
            />
            <JsonButton filename={`shops-page-${sp.page}.json`} data={shops} />
          </span>
        }
      />

      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th>Item</th>
              <th>Owner</th>
              <th>Type</th>
              <th className="amount">Buy</th>
              <th className="amount">Sell</th>
              <th className="amount">Stock</th>
              <th>Location</th>
              <th className="ts">Seen</th>
            </tr>
          </thead>
          <tbody>
            {shops.length === 0 && (
              <tr><td colSpan={8} className="empty">No shops match.</td></tr>
            )}
            {shops.map((s) => (
              <tr key={s.shop_id}>
                <td>
                  <Link href={`/chestshop/items/${encodeURIComponent(s.item_key)}` as Route} className="rowlink cell-with-icon" prefetch={false}>
                    <ItemIcon icon={itemIconUrl(s.material, s.item_custom)} name={s.item_name ?? s.item_key} size={26} />
                    <span style={{ fontWeight: 500 }}>{s.item_name ?? s.item_key}</span>
                  </Link>
                </td>
                <td>{s.owner_name ?? (s.admin_shop ? 'Admin shop' : '—')}</td>
                <td>
                  {s.admin_shop ? <span className="badge">admin</span> : null}
                  {s.shop_account_type && <span className={`badge badge-${s.shop_account_type}`}>{s.shop_account_type}</span>}
                </td>
                <td className="amount neutral">{s.buy_price ? fmtAmtFull(s.buy_price) : '—'}</td>
                <td className="amount neutral">{s.sell_price ? fmtAmtFull(s.sell_price) : '—'}</td>
                <td className="amount mono">{s.admin_shop ? '∞' : (s.current_stock ?? '—')}</td>
                <td>
                  {(() => {
                    const url = blueMapUrl(mapBase, s.world, s.sign_x, s.sign_y, s.sign_z);
                    const label = `${s.world ?? '—'} ${s.sign_x},${s.sign_y},${s.sign_z}`;
                    return url ? (
                      <a href={url} target="_blank" rel="noopener noreferrer" className="map-link mono small" title="Open in map">
                        <span className="map-pin" aria-hidden>📍</span><span>{label}</span>
                      </a>
                    ) : (
                      <span className="mono small" style={{ color: 'var(--fg-muted)' }}>{label}</span>
                    );
                  })()}
                </td>
                <td className="ts">{fmtTs(s.last_seen)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <Pagination
        page={sp.page}
        totalPages={totalPages}
        totalItems={total}
        basePath="/chestshop/shops"
        searchParams={{ q: sp.q, item: sp.item, buyable: sp.buyable, inStock: sp.inStock, limit: String(sp.limit) }}
      />
    </>
  );
}

