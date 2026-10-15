'use client';
// Searchable grid of market item cards. Each card: Minecraft item icon, name +
// material, a price sparkline, current avg price with up/down movement, and
// trade/volume stats. Client-side filter for instant search over the set.

import Link from 'next/link';
import type { Route } from 'next';
import { MiniSpark } from '@/components/charts/MiniSpark';
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

export function ItemCards({ items, days }: { items: ItemCard[]; days: number }) {
  return (
    <div className="card">
      <div className="card-title">
        Top traded items
        <span className="sub">price trend · last {days} days</span>
      </div>

      {items.length === 0 ? (
        <div className="state-empty" style={{ padding: 24 }}>No trades in this window. Browse the full catalogue or try another period.</div>
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
                href={`/chestshop/items/${encodeURIComponent(i.item_key)}` as Route}
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
                  <MiniSpark values={i.series.map((s) => s.price)} stroke={stroke} width={240} height={40} />
                </div>
                <div className="item-card-stats">
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
              </Link>
            );
          })}
        </div>
      )}
    </div>
  );
}
