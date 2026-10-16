import Link from 'next/link';
import type { MarketShopRow } from '@/lib/sql/market';
import { shopUnitPrice, formatShopPrice } from '@/lib/shopPrice';
import { LocalTimestamp } from '@/components/LocalTimestamp';
import { blueMapUrl } from '@/lib/serverIdentity';
import { CopyLocation } from './CopyLocation';
export function ShopOffers({ shops, direction, units = 1, mapBase }: { shops: MarketShopRow[]; direction: 'buy' | 'sell'; units?: number; mapBase: string }) {
  return <div className="offer-grid">
    {shops.length === 0 && <p className="state-empty">No observed offers match. Try another direction or clear the stock filter.</p>}
    {shops.map(s => {
      const unit = shopUnitPrice(direction === 'buy' ? s.buy_price : s.sell_price, s.batch_qty);
      const location = `${s.world ?? 'Unknown world'} ${s.sign_x}, ${s.sign_y}, ${s.sign_z}`;
      const url = blueMapUrl(mapBase, s.world, s.sign_x, s.sign_y, s.sign_z);
      return <article className="card offer-card" key={s.shop_id}>
        <h2 className="card-title"><Link href={`/chestshop/items/${encodeURIComponent(s.item_key)}`}>{s.item_name ?? s.item_key}</Link></h2>
        <p className="offer-price">{formatShopPrice(unit === null ? null : unit * units)} <span className="small muted">/ {units === 1 ? 'item' : '64 items'}</span></p>
        <p>{direction === 'buy' ? 'Buy from' : 'Sell to'} {s.admin_shop ? 'Admin shop' : s.owner_name ?? 'Unknown owner'}</p>
        <dl><dt>Sign batch</dt><dd>{s.batch_qty} items · {formatShopPrice(Number(direction === 'buy' ? s.buy_price : s.sell_price))}</dd>
          <dt>Observed stock</dt><dd>{s.admin_shop ? 'Unlimited' : s.current_stock === null ? 'Unknown' : s.current_stock}</dd>
          <dt>Space available</dt><dd>{s.admin_shop ? 'Unlimited' : s.current_space === null ? 'Unknown' : `${s.current_space} items`}</dd>
          <dt>Stock and space checked</dt><dd><LocalTimestamp value={s.stock_at} /></dd><dt>Shop seen</dt><dd><LocalTimestamp value={s.last_seen} /></dd></dl>
        <p>{url ? <a href={url} target="_blank" rel="noopener noreferrer">{location} ↗</a> : <span>{location}</span>}</p>
        <CopyLocation location={location} />
      </article>;
    })}
  </div>;
}
