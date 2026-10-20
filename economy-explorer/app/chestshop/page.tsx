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
import { EmptyState } from '@democracycraft/design-system';

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
        <span className="sub">{fmtN(total)} items to explore</span>
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

      <div className="section-heading"><div><h2>{sp.q ? `Results for “${sp.q}”` : 'The item catalogue'}</h2><p>Choose an item to compare shops and explore its price history.</p></div><span className="muted small">{fmtN(total)} results · A–Z by item key</span></div>
      {items.length === 0 ? <EmptyState>
        <span className="empty-symbol" aria-hidden>⌕</span>
        <h2>No items found</h2><p>Try a shorter name or a material such as diamond. You can also start fresh.</p>
        <Link href="/chestshop" className="btn btn-primary">Browse all items</Link>
      </EmptyState> : <div className="catalogue-grid">
        {items.map(i => <Link key={i.item_key} href={marketItemHref(i.item_key, returnTo) as Route} className="catalogue-card" prefetch={false}>
          <div className="catalogue-identity"><span className="item-tile"><ItemIcon icon={itemIconUrl(i.material, i.item_custom)} name={i.item_name ?? i.item_key} size={40} /></span><div><h2>{i.item_name ?? i.item_key}</h2><span className="muted small">{i.item_custom ? 'Custom item' : (i.material ?? 'Item').replaceAll('_', ' ').toLowerCase()}</span></div></div>
          <div className="catalogue-stats"><span><strong>{fmtN(i.trade_count)}</strong> recorded trades</span><span title="All-time traded volume">{fmtAmtFull(i.total_volume)} volume</span></div>
          <span className="item-card-action">View prices &amp; shops <span aria-hidden>→</span></span>
        </Link>)}
      </div>}

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

