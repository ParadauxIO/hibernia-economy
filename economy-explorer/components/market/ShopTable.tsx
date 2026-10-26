import { EntityLink } from '@democracycraft/design-system/next';
import { DataTable, type Column } from '@democracycraft/design-system/table';
import { Tag, When } from '@democracycraft/design-system';
import { itemHref, previewHref } from '@/lib/entityLinks';
import { itemIconUrl } from '@/lib/itemIcon';
import { blueMapUrl } from '@/lib/serverIdentity';
import { availability } from '@/lib/market/availability';
import { formatShopPrice, shopUnitPrice } from '@/lib/shopPrice';

export interface ShopTableRow {
  shop_id: number;
  item_key: string;
  item_name: string | null;
  material: string | null;
  item_custom: number | null;
  world: string | null;
  sign_x: number | null;
  sign_y: number | null;
  sign_z: number | null;
  buy_price: string | null;
  sell_price: string | null;
  batch_qty: number;
  current_stock: number | null;
  current_space?: number | null;
  stock_at?: Date | string | null;
  admin_shop?: number | boolean | null;
  last_seen?: Date | string | null;
}

/**
 * Shop signs as a table: item, unit prices (signs quote a batch), observed
 * stock and a map link. Prices are as last observed, not live quotes.
 */
export function ShopTable<R extends ShopTableRow>({ id, label, shops, mapBase, showItem = true, extra = [], direction = 'buy' }: {
  id: string;
  label: string;
  shops: readonly R[];
  mapBase: string;
  showItem?: boolean;
  /** Extra columns after the item (an owner column on the item page). */
  extra?: Column<R>[];
  /** Which side the visitor is on: buying reads stock, selling reads the shop's free space. */
  direction?: 'buy' | 'sell';
}) {
  const columns: Column<R>[] = [
    ...(showItem ? [{
      key: 'item',
      header: 'Item',
      cell: (s: R) => <EntityLink kind="item" href={itemHref(s.item_key)} name={s.item_name ?? s.item_key}
        icon={itemIconUrl(s.material, s.item_custom ?? 0)} preview={previewHref('item', s.item_key)} />,
    }] : []),
    ...extra,
    {
      key: 'buy', header: 'Buy, per item', numeric: true,
      cell: (s) => s.buy_price === null ? <span className="muted">—</span> : formatShopPrice(shopUnitPrice(s.buy_price, s.batch_qty)),
    },
    {
      key: 'sell', header: 'Sell, per item', numeric: true,
      cell: (s) => s.sell_price === null ? <span className="muted">—</span> : formatShopPrice(shopUnitPrice(s.sell_price, s.batch_qty)),
    },
    {
      key: 'stock', header: direction === 'buy' ? 'Stock' : 'Space', secondary: true,
      cell: (s) => {
        const a = availability(s, direction);
        const count = a.count !== null ? <span className="dt-sub">{` · ${a.count.toLocaleString('en-US')}`}</span> : null;
        if (a.tone === 'warn') return <><Tag tone="warn">{a.label}</Tag>{count}</>;
        return <span className={a.tone === 'neutral' ? 'muted' : undefined}>{a.label}{count}</span>;
      },
    },
    {
      key: 'where', header: 'Location', secondary: true,
      cell: (s) => {
        const text = `${s.world ?? '—'} ${s.sign_x ?? ''}, ${s.sign_y ?? ''}, ${s.sign_z ?? ''}`;
        const url = blueMapUrl(mapBase, s.world, s.sign_x, s.sign_y, s.sign_z);
        return url ? <a href={url} target="_blank" rel="noopener noreferrer" className="num" aria-label={`Open ${text} on the map`}>{text} <span aria-hidden="true">↗</span></a> : <span className="num">{text}</span>;
      },
    },
    ...(shops.some((s) => s.last_seen) ? [{
      key: 'seen', header: 'Seen', secondary: true, optional: true,
      cell: (s: R) => <When value={s.last_seen ?? null} />,
    }] : []),
  ];
  return <DataTable id={id} label={label} columns={columns} rows={shops} rowKey={(s) => s.shop_id} density="compact"
    empty={<p className="dt-empty-text">No shops observed.</p>} />;
}
