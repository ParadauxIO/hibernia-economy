import { marketItemHref } from '@/lib/marketNavigation';
import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import { getItemCatalogue } from '@/lib/services/market';
import Link from 'next/link';
import type { Route } from 'next';
import { z } from 'zod';
import { fmtAmtFull, fmtN } from '@/lib/format';
import { Toolbar } from '@/components/Toolbar';
import { Pagination } from '@democracycraft/design-system/next';
import { SectionTabs } from '@/components/SectionTabs';
import { CsvButton } from '@/components/CsvButton';
import { JsonButton } from '@/components/JsonButton';
import { ItemIcon } from '@/components/market/ItemIcon';
import { itemIconUrl } from '@/lib/itemIcon';
import { EmptyState, TableWrap } from '@democracycraft/design-system';

const SP_SCHEMA = z.object({
  page: z.coerce.number().int().min(1).catch(1),
  limit: z.coerce.number().int().min(1).max(200).catch(50),
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
  const returnTo = `/chestshop?${new URLSearchParams({ ...(sp.q ? { q: sp.q } : {}), page: String(sp.page), limit: String(sp.limit) })}`;

  return (
    <>
      <div className="page-heading">
        <h1>Browse items</h1>
        <span className="sub">{fmtN(total)} items</span>
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

      {items.length === 0 ? <EmptyState>
        <h2>No items found</h2><p>Try a different item name or material.</p>
        <Link href="/chestshop" className="btn btn-primary">Browse all items</Link>
      </EmptyState> : <TableWrap label="Item catalogue">
        <table className="data-table directory-table catalogue-table" aria-label="Item catalogue">
          <thead><tr><th scope="col">Item</th><th scope="col" data-secondary>Material</th><th scope="col" className="amount" data-secondary>Trades</th><th scope="col" className="amount" data-secondary>Quantity</th><th scope="col" className="amount">All-time volume</th></tr></thead>
          <tbody>{items.map(i => <tr key={i.item_key}>
            <td><Link href={marketItemHref(i.item_key, returnTo) as Route} className="rowlink cell-with-icon" prefetch={false}><ItemIcon icon={itemIconUrl(i.material, i.item_custom)} name={i.item_name ?? i.item_key} size={26} /><span>{i.item_name ?? i.item_key}</span>{i.item_custom ? <span className="badge">Custom</span> : null}</Link>
              <details className="mobile-only directory-details"><summary>Trading details</summary><p>Material: {i.material ?? '—'}</p><p>{fmtN(i.trade_count)} trades · {fmtN(i.total_quantity)} items</p></details>
            </td>
            <td className="mono small" data-secondary>{i.material ?? '—'}</td>
            <td className="amount mono" data-secondary>{fmtN(i.trade_count)}</td>
            <td className="amount mono" data-secondary>{fmtN(i.total_quantity)}</td>
            <td className="amount mono">{fmtAmtFull(i.total_volume)}</td>
          </tr>)}</tbody>
        </table>
      </TableWrap>}

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

