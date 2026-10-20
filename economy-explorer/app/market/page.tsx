import Link from 'next/link';
import { buildMetadata } from '@/lib/metadata';
import { getPopularItems } from '@/lib/services/market';
import { SectionTabs } from '@/components/SectionTabs';
import { Button, Input, PageHeading, WindowTabs } from '@democracycraft/design-system';
import { ItemCards } from '@/components/market/ItemCards';
import { itemIconUrl } from '@/lib/itemIcon';
export const dynamic = 'force-dynamic';
export async function generateMetadata() { return buildMetadata({ title: 'Market', description: 'Find items, prices and shops on {server}.', path: '/market' }); }
export default async function MarketPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  const days = sp.days === '7' ? 7 : 30;
  const items = await getPopularItems(days);
  return <>
    <PageHeading title="Market"><SectionTabs /></PageHeading>
    <section className="market-hero" aria-labelledby="market-welcome">
      <div className="market-hero-main">
        <span className="eyebrow">The player market</span>
        <h2 id="market-welcome">Your next trade<br />starts here.</h2>
        <p>Find the item you need. Compare shops. Make your next move in-game.</p>
        <form action="/chestshop" role="search" aria-label="Items">
          <label htmlFor="market-item-search" className="field-label">Find an item</label>
          <div className="market-search-box"><Input id="market-item-search" name="q" type="search" placeholder="Try diamond or oak log…" /><Button variant="primary" type="submit">Search all items <span aria-hidden>→</span></Button></div>
        </form>
        <div className="market-hero-links"><Link href="/chestshop">Browse all items <span aria-hidden>↗</span></Link><Link href="/chestshop/shops">Go straight to shops <span aria-hidden>↗</span></Link></div>
      </div>
      <ol className="market-steps" aria-label="How to find a trade">
        <li><span aria-hidden>01</span><div><strong>Find your item</strong><p>Search the catalogue or explore popular trades.</p></div></li>
        <li><span aria-hidden>02</span><div><strong>Compare shops</strong><p>Choose buying or selling. Check prices and observed stock.</p></div></li>
        <li><span aria-hidden>03</span><div><strong>Visit the shop</strong><p>Open the map or copy coordinates to visit the shop.</p></div></li>
      </ol>
    </section>
    <div className="section-heading"><div><span className="eyebrow">Discover</span><h2>Popular items</h2><p>The most traded items in the last {days} days.</p></div><WindowTabs basePath="/market" days={days} /></div>
    <ItemCards items={items.map(i => ({ ...i, icon: itemIconUrl(i.material, i.item_custom) }))} days={days} returnTo={`/market?days=${days}`} />
    <Link href="/market/activity" className="explore-banner"><div><strong>Curious about the bigger picture?</strong><span>Explore trading activity, volume, and trends across the market.</span></div><span aria-hidden>→</span></Link>
  </>;
}
