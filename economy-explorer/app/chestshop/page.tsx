import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import { getItemCatalogue } from '@/lib/services/market';
import Link from 'next/link';
import type { Route } from 'next';
import { z } from 'zod';
import { fmtAmtFull, fmtN } from '@/lib/format';
import { Toolbar } from '@/components/Toolbar';
import { Pagination } from '@/components/Pagination';
import { SectionTabs } from '@/components/SectionTabs';
import { CsvButton } from '@/components/CsvButton';
import { JsonButton } from '@/components/JsonButton';
import { ItemIcon } from '@/components/market/ItemIcon';
import { itemIconUrl } from '@/lib/itemIcon';

const SP_SCHEMA = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(200).default(50),
  q: z.string().trim().optional().transform(v => v || undefined),
});

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: "ChestShop", description: "ChestShop market activity on {server} — shops, sales and items.", path: "/chestshop" });
}

export default async function ChestShopPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const sp = SP_SCHEMA.parse(flattenSearchParams(await searchParams));
  const offset = (sp.page - 1) * sp.limit;
  const [items, total] = await getItemCatalogue(sp.q ?? null, sp.limit, offset);
  const totalPages = Math.max(1, Math.ceil(total / sp.limit));

  return (
    <>
      <div className="page-heading">
        <h1>Browse items</h1>
        <span className="sub">{fmtN(total)} items · alphabetical by item key</span>
        <SectionTabs />
      </div>

      <Toolbar
        searchPlaceholder="Search items, materials, keys…"
        right={
          <span className="toolbar-export">
            <CsvButton
              filename={`chestshop-items-page-${sp.page}.csv`}
              headers={['Item key', 'Item name', 'Material', 'Trades', 'Quantity', 'Volume']}
              rows={items.map((i) => [
                i.item_key,
                i.item_name ?? '',
                i.material ?? '',
                i.trade_count,
                i.total_quantity,
                i.total_volume,
              ])}
            />
            <JsonButton filename={`chestshop-items-page-${sp.page}.json`} data={items} />
          </span>
        }
      />

      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th>Item</th>
              <th>Material</th>
              <th className="amount">Trades</th>
              <th className="amount">Quantity</th>
              <th className="amount">All-time volume</th>
            </tr>
          </thead>
          <tbody>
            {items.length === 0 && (
              <tr><td colSpan={5} className="empty">No items match.</td></tr>
            )}
            {items.map((i) => (
              <tr key={i.item_key}>
                <td>
                  <Link href={`/chestshop/items/${encodeURIComponent(i.item_key)}` as Route} className="rowlink cell-with-icon" prefetch={false}>
                    <ItemIcon icon={itemIconUrl(i.material, i.item_custom)} name={i.item_name ?? i.item_key} size={26} />
                    <span style={{ fontWeight: 500 }}>{i.item_name ?? i.item_key}</span>
                    {i.item_custom ? <span className="badge">custom</span> : null}
                  </Link>
                </td>
                <td><span className="mono small" style={{ color: 'var(--fg-muted)' }}>{i.material ?? '—'}</span></td>
                <td className="amount mono">{fmtN(i.trade_count)}</td>
                <td className="amount mono">{fmtN(i.total_quantity)}</td>
                <td className="amount neutral">{fmtAmtFull(i.total_volume)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <Pagination
        page={sp.page}
        totalPages={totalPages}
        totalItems={total}
        basePath="/chestshop"
        searchParams={{ q: sp.q, limit: String(sp.limit) }}
      />
    </>
  );
}

