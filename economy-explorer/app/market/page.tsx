import Link from 'next/link';
import { buildMetadata } from '@/lib/metadata';
import { getPopularItems } from '@/lib/services/market';
import { SectionTabs } from '@/components/SectionTabs';
import { WindowTabs } from '@/components/WindowTabs';
import { ItemCards } from '@/components/market/ItemCards';
import { itemIconUrl } from '@/lib/itemIcon';
export const dynamic = 'force-dynamic';
export async function generateMetadata() { return buildMetadata({ title: 'Market', description: 'Find items, prices and shops on {server}.', path: '/market' }); }
export default async function MarketPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  const days = sp.days === '7' ? 7 : 30;
  const items = await getPopularItems(days);
  return <>
    <div className="page-heading"><h1>Market</h1><SectionTabs /></div>
    <section className="card market-search"><h2>What would you like to trade?</h2>
      <p>Search the full item catalogue, then compare places to buy or sell.</p>
      <form action="/chestshop" role="search" aria-label="Items"><label htmlFor="market-item-search">Find an item</label><div className="search-row"><input id="market-item-search" name="q" type="search" placeholder="Item name or material…" className="toolbar-search" /><button className="btn btn-primary" type="submit">Search all items</button></div></form>
      <p><Link href="/chestshop">Browse all items →</Link> · <Link href="/chestshop/shops">Find shops →</Link></p>
    </section>
    <div className="page-heading"><h2>Popular items</h2><WindowTabs basePath="/market" days={days} /></div>
    <p className="page-intro">Historical daily average prices per item, not current shop offers. Movement compares the first and latest trading day in this window.</p>
    <ItemCards items={items.map(i => ({ ...i, icon: itemIconUrl(i.material, i.item_custom) }))} days={days} />
    <p><Link href="/market/activity">Explore market activity and totals →</Link></p>
  </>;
}
