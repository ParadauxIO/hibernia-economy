import Link from 'next/link';
import { EmptyState } from '@democracycraft/design-system';
import type { MarketShopRow } from '@/lib/sql/market';
import { shopUnitPrice, formatShopPrice } from '@/lib/shopPrice';
import { marketItemHref } from '@/lib/marketNavigation';
import { LocalTimestamp } from '@/components/LocalTimestamp';
import { blueMapUrl } from '@/lib/serverIdentity';
import { CopyLocation } from './CopyLocation';

/** Observations are evidence, never a promise that a trade can still be fulfilled. */
function availability(shop: MarketShopRow, direction: 'buy' | 'sell') {
  if (shop.admin_shop) return { label: 'Unlimited', tone: 'neutral', detail: 'Admin shop' };
  const count = direction === 'buy' ? shop.current_stock : shop.current_space;
  const at = shop.stock_at ? new Date(shop.stock_at).getTime() : NaN;
  if (count === null || !Number.isFinite(at) || at > Date.now() || shop.batch_qty < 1) return { label: 'Availability unknown', tone: 'neutral', detail: 'Check the shop in-game' };
  if (Date.now() - at > 24 * 60 * 60 * 1000) return { label: 'Recheck availability', tone: 'warning', detail: 'Observation is over 24 hours old' };
  if (count < shop.batch_qty) return { label: direction === 'buy' ? 'Below one batch' : 'Limited space', tone: 'warning', detail: `${count.toLocaleString()} items observed` };
  return { label: direction === 'buy' ? 'Stock observed' : 'Space observed', tone: 'positive', detail: `${count.toLocaleString()} items · availability may change` };
}

export function ShopOffers({ shops, direction, units = 1, mapBase, returnTo, resetHref = '/chestshop/shops' }: { shops: MarketShopRow[]; direction: 'buy' | 'sell'; units?: number; mapBase: string; returnTo?: string; resetHref?: string }) {
  if (shops.length === 0) return <EmptyState><span className="empty-symbol" aria-hidden>⌕</span><h3>No observed offers match</h3><p>Try the other trade direction or broaden your search. A shop may not have been observed yet.</p><Link href={resetHref} className="btn">Browse more shops</Link></EmptyState>;
  return <div className="offer-grid">
    {shops.map(s => {
      const unit = shopUnitPrice(direction === 'buy' ? s.buy_price : s.sell_price, s.batch_qty);
      const location = `${s.world ?? 'Unknown world'} ${s.sign_x}, ${s.sign_y}, ${s.sign_z}`;
      const url = blueMapUrl(mapBase, s.world, s.sign_x, s.sign_y, s.sign_z);
      const state = availability(s, direction);
      return <article className="card offer-card" key={s.shop_id}>
        <div className="offer-identity"><h2 className="card-title"><Link href={marketItemHref(s.item_key, returnTo, returnTo ? direction : undefined)}>{s.item_name ?? s.item_key}</Link></h2><p>{direction === 'buy' ? 'Buy from' : 'Sell to'} <strong>{s.admin_shop ? 'Admin shop' : s.owner_name ?? 'Unknown owner'}</strong></p></div>
        <div className="offer-price-block"><span className="field-label">{direction === 'buy' ? 'You pay' : 'You receive'}</span><p className="offer-price">{formatShopPrice(unit === null ? null : unit * units)} <span className="small muted">/ {units === 1 ? 'item' : '64 items'}</span></p><span className="small muted">{units === 1 ? 'Normalized unit price' : 'Comparison estimate'} · sign batch: {s.batch_qty}</span></div>
        <div className="offer-availability"><span className={`availability availability-${state.tone}`}><span aria-hidden>●</span> {state.label}</span><p>{state.detail}</p>{s.stock_at && <span className="small muted">Checked <LocalTimestamp value={s.stock_at} /></span>}</div>
        <div className="offer-actions"><p className="offer-location">{location}</p><div className="offer-action-buttons">{url && <a className="btn btn-primary" href={url} target="_blank" rel="noopener noreferrer" aria-label={`Open map for ${location}`}>Open map <span aria-hidden>↗</span></a>}<CopyLocation location={location} /></div></div>
        <details className="offer-details"><summary>Stock and sign details</summary><dl>
          <dt>Sign batch</dt><dd>{s.batch_qty} items · {formatShopPrice((direction === 'buy' ? s.buy_price : s.sell_price) === null ? null : Number(direction === 'buy' ? s.buy_price : s.sell_price))}</dd>
          <dt>Observed stock</dt><dd>{s.admin_shop ? 'Unlimited' : s.current_stock === null ? 'Unknown' : s.current_stock}</dd>
          <dt>Space available</dt><dd>{s.admin_shop ? 'Unlimited' : s.current_space === null ? 'Unknown' : `${s.current_space} items`}</dd>
          <dt>Stock and space checked</dt><dd><LocalTimestamp value={s.stock_at} /></dd><dt>Shop seen</dt><dd><LocalTimestamp value={s.last_seen} /></dd>
        </dl></details>
      </article>;
    })}
  </div>;
}
