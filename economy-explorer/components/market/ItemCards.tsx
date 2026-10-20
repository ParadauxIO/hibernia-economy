'use client';
// Popular item cards with historical prices and links into shop comparison.

import Link from 'next/link';
import type { Route } from 'next';
import { EmptyState, MiniSpark } from '@democracycraft/design-system';
import { marketItemHref } from '@/lib/marketNavigation';
import { ItemIcon } from '@/components/market/ItemIcon';
import { fmtAmtFull, fmtN, fmtPct } from '@/lib/format';

export interface ItemCard {
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

export function ItemCards({ items, days, returnTo }: { items: ItemCard[]; days: number; returnTo?: string }) {
  return (
    <div className="market-items">
      <p className="market-price-note"><span>price trend · last {days} days</span> · Historical daily averages, not current shop quotes.</p>

      {items.length === 0 ? (
        <EmptyState style={{ padding: 24 }}>No trades in this window. <Link href="/chestshop">Browse the full catalogue</Link> or try another period.</EmptyState>
      ) : (
        <div className="item-card-grid">
          {items.map((i) => {
            const dir =
              i.price_change_pct == null ? 'flat' : i.price_change_pct > 0.001 ? 'up' : i.price_change_pct < -0.001 ? 'down' : 'flat';
            const stroke = dir === 'up' ? 'var(--good)' : dir === 'down' ? 'var(--bad)' : 'var(--accent)';
            const label = i.item_name ?? i.item_key;
            return (
              <Link
                key={i.item_key}
                href={marketItemHref(i.item_key, returnTo) as Route}
                className="item-card"
                prefetch={false}
              >
                <div className="item-card-head">
                  <ItemIcon icon={i.icon} name={label} />
                  <div className="item-card-titles">
                    <span className="item-card-name">{label}</span>

                  </div>
                </div>
                <div className="item-card-spark">
                  {i.series.length > 1 ? <MiniSpark values={i.series.map((s) => s.price)} stroke={stroke} width={240} height={40} /> : <span className="small muted">More trading days will reveal a trend.</span>}
                </div>
                <div className="item-card-stats">
                  <span className="field-label">Latest daily average</span>
                  <div className="item-card-price">
                    <span className="item-card-price-val">{i.current_price != null ? fmtAmtFull(i.current_price) : '—'}</span><span className="muted small"> / item</span>
                    {i.price_change_pct != null && (
                      <span className={`kpi-delta ${dir}`}>
                        {dir === 'up' ? '▲' : dir === 'down' ? '▼' : '→'} {fmtPct(Math.abs(i.price_change_pct))}
                      </span>
                    )}
                  </div>
                  <div className="item-card-meta">
                    {fmtN(i.trade_count)} trades · {fmtAmtFull(i.total_volume)}
                  </div>
                </div>
                <span className="item-card-action">Compare shops <span aria-hidden>→</span></span>
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}
