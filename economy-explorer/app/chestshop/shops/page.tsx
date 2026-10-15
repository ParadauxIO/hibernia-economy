import Link from 'next/link';
import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import { z } from 'zod';
import { getShopOffers } from '@/lib/services/market';
import { fmtN } from '@/lib/format';
import { Toolbar, FilterSelect } from '@/components/Toolbar';
import { Pagination } from '@/components/Pagination';
import { SectionTabs } from '@/components/SectionTabs';
import { ShopOffers } from '@/components/market/ShopOffers';
import { resolveTheme } from '@/lib/theme';
import { serverIdentity } from '@/lib/serverIdentity';
const SP_SCHEMA = z.object({
  page: z.coerce.number().int().min(1).catch(1), limit: z.coerce.number().int().min(1).max(50).catch(24),
  q: z.string().trim().optional().transform(v => v || undefined), item: z.string().optional(),
  direction: z.enum(['buy', 'sell']).catch('buy'), units: z.enum(['1', '64']).catch('1'),
  inStock: z.enum(['true', 'false']).optional(),
});
export const dynamic = 'force-dynamic';
export async function generateMetadata() { return buildMetadata({ title: 'Find shops', description: 'Compare observed shop offers on {server}.', path: '/chestshop/shops' }); }
export default async function ShopsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = SP_SCHEMA.parse(flattenSearchParams(await searchParams));
  const [[shops, total], theme] = await Promise.all([getShopOffers({ itemKey: sp.item ?? null, material: null, firmId: null, ownerUuid: null, buyable: false, direction: sp.direction, inStock: sp.direction === 'buy' && sp.inStock === 'true', search: sp.q ?? null, limit: sp.limit, offset: (sp.page - 1) * sp.limit }), resolveTheme()]);
  return <>
    <div className="page-heading"><h1>Find shops</h1><span className="sub">{fmtN(total)} observed offers</span><SectionTabs /></div>
    <p className="page-intro">{sp.direction === 'buy' ? 'Buy from shops, ordered by lowest unit price (known insufficient stock last).' : 'Sell your items to shops, ordered by highest unit price.'} Prices and stock are last observed, not guaranteed live availability. A 64-item comparison is an estimate; trades use the sign batch and some items stack differently.</p>
    {sp.item && <p>Item: <strong>{sp.item}</strong> · <Link href="/chestshop/shops">Clear item filter</Link></p>}
    <Toolbar searchPlaceholder="Search items or materials…" filters={<>
      <FilterSelect paramKey="direction" ariaLabel="Trade direction" options={[{ value: 'buy', label: 'I want to buy' }, { value: 'sell', label: 'I want to sell' }]} />
      <FilterSelect paramKey="units" ariaLabel="Price units" options={[{ value: '1', label: 'Price per item' }, { value: '64', label: 'Price per 64 items' }]} />
      {sp.direction === 'buy' && <FilterSelect paramKey="inStock" ariaLabel="Stock" options={[{ value: '', label: 'All stock observations' }, { value: 'true', label: 'Enough stock for a batch' }]} />}
    </>} />
    <ShopOffers shops={shops} direction={sp.direction} units={Number(sp.units)} mapBase={serverIdentity(theme).map} />
    <Pagination page={sp.page} totalPages={Math.max(1, Math.ceil(total / sp.limit))} totalItems={total} basePath="/chestshop/shops" searchParams={{ q: sp.q, item: sp.item, direction: sp.direction, units: sp.units, inStock: sp.inStock, limit: String(sp.limit) }} />
  </>;
}
