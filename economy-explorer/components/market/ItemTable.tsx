import Link from 'next/link';
import type { Route } from 'next';
import { EmptyState, MiniSpark, TableWrap } from '@democracycraft/design-system';
import { marketItemHref } from '@/lib/marketNavigation';
import { ItemIcon } from '@/components/market/ItemIcon';
import { fmtAmtFull, fmtN, fmtPct } from '@/lib/format';

export interface PopularItem {
  item_key: string;
  item_name: string | null;
  material: string | null;
  icon: string | null;
  trade_count: number;
  total_quantity: number;
  total_volume: string;
  current_price: number | null;
  price_change_pct: number | null;
  series: { day: string; price: number }[];
}

export function ItemTable({ items, days, returnTo }: { items: PopularItem[]; days: number; returnTo?: string }) {
  return <div className="market-items">
    <p className="market-price-note">Prices are historical daily averages per item · last {days} days.</p>
    {items.length === 0 ? <EmptyState>No trades in this window. <Link href="/chestshop">Browse the full catalogue</Link> or try another period.</EmptyState> :
      <TableWrap label="Most traded items">
        <table className="data-table directory-table market-table" aria-label="Most traded items">
          <thead><tr><th scope="col">Item</th><th scope="col" className="amount">Latest average</th><th scope="col" data-secondary>Change</th><th scope="col" className="amount" data-secondary>Trades</th><th scope="col" className="amount" data-secondary>Volume</th><th scope="col" data-secondary>Price trend</th></tr></thead>
          <tbody>{items.map(i => {
            const dir = i.price_change_pct == null ? 'flat' : i.price_change_pct > 0.001 ? 'up' : i.price_change_pct < -0.001 ? 'down' : 'flat';
            const stroke = dir === 'up' ? 'var(--good)' : dir === 'down' ? 'var(--bad)' : 'var(--accent)';
            const label = i.item_name ?? i.item_key;
            const delta = i.price_change_pct == null ? '—' : <span className={`kpi-delta ${dir}`}>{dir === 'up' ? '▲' : dir === 'down' ? '▼' : '→'} {fmtPct(Math.abs(i.price_change_pct))}</span>;
            return <tr key={i.item_key}>
              <td><Link href={marketItemHref(i.item_key, returnTo) as Route} className="rowlink cell-with-icon" prefetch={false}><ItemIcon icon={i.icon} name={label} size={26} /><span className="item-name">{label}</span></Link>
                <details className="mobile-only directory-details"><summary>Trading details</summary><p>Change: {delta}</p><p>{fmtN(i.trade_count)} trades</p><p>Volume: {fmtAmtFull(i.total_volume)}</p></details>
              </td>
              <td className="amount mono item-price">{i.current_price != null ? fmtAmtFull(i.current_price) : '—'}</td>
              <td data-secondary>{delta}</td>
              <td className="amount mono item-trades" data-secondary>{fmtN(i.trade_count)}</td>
              <td className="amount mono item-volume" data-secondary>{fmtAmtFull(i.total_volume)}</td>
              <td className="item-trend" data-secondary>{i.series.length > 1 ? <MiniSpark values={i.series.map(s => s.price)} stroke={stroke} width={100} height={28} /> : <span className="muted" title="Not enough daily observations">—</span>}</td>
            </tr>;
          })}</tbody>
        </table>
      </TableWrap>}
  </div>;
}
