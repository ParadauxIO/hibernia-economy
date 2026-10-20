import { buildMetadata } from '@/lib/metadata';
import { getPopularItems } from '@/lib/services/market';
import { SectionTabs } from '@/components/SectionTabs';
import { Button, Input, PageHeading, WindowTabs } from '@democracycraft/design-system';
import { ItemTable } from '@/components/market/ItemTable';
import { itemIconUrl } from '@/lib/itemIcon';
export const dynamic = 'force-dynamic';
export async function generateMetadata() { return buildMetadata({ title: 'Market', description: 'Find items, prices and shops on {server}.', path: '/market' }); }
export default async function MarketPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  const days = sp.days === '7' ? 7 : 30;
  const items = await getPopularItems(days);
  return <>
    <PageHeading title="Market"><SectionTabs /></PageHeading>
    <form className="market-search" action="/chestshop" role="search" aria-label="Items">
      <label htmlFor="market-item-search" className="field-label">Find an item</label>
      <div className="market-search-controls"><Input id="market-item-search" name="q" type="search" placeholder="Search items or materials…" /><Button type="submit">Search all items</Button></div>
    </form>
    <div className="section-heading"><h2>Most traded items</h2><WindowTabs basePath="/market" days={days} /></div>
    <ItemTable items={items.map(i => ({ ...i, icon: itemIconUrl(i.material, i.item_custom) }))} days={days} returnTo={`/market?days=${days}`} />
  </>;
}
