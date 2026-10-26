import { BackLink, Pagination } from '@democracycraft/design-system/next';
import { marketReturnPath } from '@/lib/marketNavigation';
import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import { z } from 'zod';
import Link from 'next/link';
import { getShopOffers, getItemHistory } from '@/lib/services/market';
import { ShopOffers } from '@/components/market/ShopOffers';
import { listItemSales, listSamplesInRange } from '@/lib/sql/market';
import { fmtAmtFull, fmtN } from '@/lib/format';
import { divRound, formatMoney, sumBy } from '@/lib/money';
import { LocalTimestamp } from '@/components/LocalTimestamp';
import { bucketSamples, type CandleInterval } from '@/lib/derived';

import { PriceLine } from '@/components/charts/AreaTimeline';
import { Candlestick } from '@/components/charts/Candlestick';
import { SectionTabs } from '@/components/SectionTabs';
import { ItemIcon } from '@/components/market/ItemIcon';
import { itemIconUrl } from '@/lib/itemIcon';
import { CsvButton } from '@/components/CsvButton';
import { JsonButton } from '@/components/JsonButton';
import { resolveTheme } from '@/lib/theme';
import { serverIdentity, blueMapUrl } from '@/lib/serverIdentity';
import { getViewer } from '@/lib/auth/viewer';
import { hasPermission } from '@/lib/auth/access';
import { TableWrap } from '@democracycraft/design-system';

const SP_SCHEMA = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(200).default(50),
  // Default to 1h: with only a few weeks of data, daily buckets give ~4 sparse
  // candles that read as broken; hourly is dense and readable.
  from: z.string().optional(),
  direction: z.enum(['buy', 'sell']).catch('buy'),
  advanced: z.enum(['true', 'false']).catch('false'),
  interval: z.enum(['1h', '4h', '1d']).default('1h'),
});

export const dynamic = 'force-dynamic';

export async function generateMetadata({ params }: { params: Promise<{ itemKey: string }> }) {
  const { itemKey } = await params;
  const raw = decodeURIComponent(itemKey);
  const name = raw.replace(/^minecraft:/, '').replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
  return buildMetadata({ title: name, description: `Price history and shops trading ${name} on {server}.`, path: `/chestshop/items/${itemKey}` });
}

export default async function ItemDetailPage({
  params,
  searchParams,
}: {
  params: Promise<{ itemKey: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { itemKey: raw } = await params;
  const itemKey = decodeURIComponent(raw);
  const sp = SP_SCHEMA.parse(flattenSearchParams(await searchParams));
  const returnTo = marketReturnPath(sp.from);
  const itemQuery = (changes: Record<string, string>) => `?${new URLSearchParams({ direction: sp.direction, advanced: sp.advanced, interval: sp.interval, from: returnTo, ...changes })}`;

  // Windows capped at 30 days (there isn't more data yet, and tighter windows
  // give denser, readable candles).
  const candleWindowDays = sp.interval === '1h' ? 7 : sp.interval === '4h' ? 14 : 30;
  const candlesTo = new Date();
  const candlesFrom = new Date(candlesTo.getTime() - candleWindowDays * 24 * 60 * 60 * 1000);

  const viewerPromise = getViewer();
  const [viewer, sales, { identity, totalSales, sellers, totalSellers, byDay }, samples, theme, [offers, offerCount]] = await Promise.all([
    viewerPromise,
    viewerPromise.then(viewer => hasPermission(viewer, 'market.figures.read')
      ? listItemSales(itemKey, sp.limit, (sp.page - 1) * sp.limit) : []),
    getItemHistory(itemKey),
    sp.advanced === 'true' ? listSamplesInRange({ itemKey, from: candlesFrom, to: candlesTo, maxRows: 5000 }) : Promise.resolve([]),
    resolveTheme(),
    getShopOffers({ itemKey, material: null, firmId: null, ownerUuid: null, buyable: false, direction: sp.direction, inStock: false, search: null, limit: 4, offset: 0 }),
  ]);
  const candles = bucketSamples(samples, sp.interval as CandleInterval);
  const mapBase = serverIdentity(theme).map;
  // Item-level aggregates (totals, price, who sells it) are public; per-seller
  // money/quantity and the individual-sale feed are financial drilldown — staff
  // only. A firm's own finance staff see their detail on the firm page.
  const showSaleFigures = hasPermission(viewer, 'market.figures.read');

  const totalPages = Math.max(1, Math.ceil(totalSales / sp.limit));
  const headerName = identity?.item_name ?? offers[0]?.item_name ?? itemKey;
  const material = identity?.material ?? offers[0]?.material ?? null;
  const icon = itemIconUrl(material, identity?.item_custom ?? offers[0]?.item_custom);
  // Item keys carry ':' / '#' (e.g. nexo:ruby_sword#02) — unsafe in filenames.
  const fileKey = itemKey.replace(/[^a-z0-9]+/gi, '_');

  // The sellers query always returns sale/quantity/volume, but those are
  // financial drilldown — gate them out of the JSON export for non-staff too
  // (a whitelist, so new financial columns can't leak by default).
  const sellersExport = showSaleFigures
    ? sellers
    : sellers.map((s) => ({
        owner_name: s.owner_name,
        admin_shop: s.admin_shop,
        shop_account_type: s.shop_account_type,
        shop_account_id: s.shop_account_id,
        shop_firm_id: s.shop_firm_id,
        shop_owner_uuid: s.shop_owner_uuid,
      }));

  const last30Volume = sumBy(byDay, (d) => d.total_volume);
  const last30Quantity = byDay.reduce((s, d) => s + d.total_quantity, 0);
  const avgPrice = last30Quantity > 0 ? divRound(last30Volume, last30Quantity) : null;

  // 30-day price movement (first vs last daily avg unit price).
  const firstDay = byDay.at(0);
  const lastDay = byDay.at(-1);
  const firstPrice = firstDay ? parseFloat(firstDay.avg_unit_price) : null;
  const lastPrice = lastDay ? parseFloat(lastDay.avg_unit_price) : null;
  const changePct = firstPrice && firstPrice > 0 && lastPrice != null ? (lastPrice - firstPrice) / firstPrice : null;
  const dir = changePct == null ? 'flat' : changePct > 0.001 ? 'up' : changePct < -0.001 ? 'down' : 'flat';

  return (
    <>
      <BackLink href={returnTo} label={returnTo.startsWith('/chestshop/shops') ? 'Back to shop results' : returnTo.startsWith('/market') ? 'Back to Market' : 'Back to item results'} />

      <div className="page-heading item-heading">
        <ItemIcon icon={icon} name={headerName} size={44} />
        <div className="item-heading-text">
          <h1>{headerName}</h1>
          <details className="small muted"><summary>Item identifiers</summary><span className="mono">{itemKey} · {material ?? 'Unknown material'}</span></details>
        </div>
        {changePct != null && (
          <span className={`kpi-delta ${dir}`} style={{ alignSelf: 'center' }}>
            {dir === 'up' ? '▲' : dir === 'down' ? '▼' : '→'} {(Math.abs(changePct) * 100).toFixed(1)}% · 30d
          </span>
        )}
        <SectionTabs />
      </div>

      <section aria-label="Observed shop offers">
        <div className="page-heading"><h2>Where to trade</h2><nav className="window-tabs" aria-label="Trade direction">
          <Link href={itemQuery({ direction: 'buy' })} aria-current={sp.direction === 'buy' ? 'true' : undefined} className={sp.direction === 'buy' ? 'active' : ''}>I want to buy</Link>
          <Link href={itemQuery({ direction: 'sell' })} aria-current={sp.direction === 'sell' ? 'true' : undefined} className={sp.direction === 'sell' ? 'active' : ''}>I want to sell</Link>
        </nav></div>
        <p className="page-intro">Observed offers, ordered by unit price. Stock and prices can change in-game.</p>
        <ShopOffers shops={offers} direction={sp.direction} mapBase={mapBase} returnTo={returnTo} resetHref={`/chestshop/shops?direction=${sp.direction}`} />
        <p className="offer-compare"><Link className="btn" href={`/chestshop/shops?item=${encodeURIComponent(itemKey)}&direction=${sp.direction}`}>Compare all {fmtN(offerCount)} offers →</Link></p>
      </section>
      <h2>Trading history</h2>
      <p className="page-intro">Historical averages are not current shop quotes. Price movement compares the first and latest trading day; daily buckets use UTC and today may be partial.</p>
      <div className="kpi-grid">
        <div className="kpi">
          <div className="kpi-label">Sales (30d)</div>
          <div className="kpi-value">{fmtN(byDay.reduce((s, d) => s + d.sales, 0))}</div>
          <div className="kpi-meta">{fmtN(totalSales)} all-time</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Volume (30d)</div>
          <div className="kpi-value">{formatMoney(last30Volume)}</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Avg price / unit (30d)</div>
          <div className="kpi-value">{avgPrice !== null ? formatMoney(avgPrice) : '—'}</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Sellers</div>
          <div className="kpi-value">{fmtN(totalSellers)}</div>
        </div>
      </div>

      {/* Daily price line — table dropped, candles + line cover the same window */}
      {byDay.length > 0 && (
        <div className="card">
          <div className="card-title">
            Daily price line <span className="sub">avg unit price · last 30 days</span>
          </div>
          <PriceLine data={byDay} />
        </div>
      )}
      <p><Link href={itemQuery({ advanced: sp.advanced === 'true' ? 'false' : 'true' })}>{sp.advanced === 'true' ? 'Hide' : 'Show'} advanced candlesticks</Link></p>
      {sp.advanced === 'true' && <>
      {/* Candlestick (OHLC) */}
      <div className="card">
        <div className="card-title">
          Candles <span className="sub">{sp.interval} buckets · {candles.length} of {samples.length} trades</span>
          <span style={{ marginLeft: 'auto', display: 'flex', gap: 4 }}>
            {(['1h', '4h', '1d'] as const).map((iv) => (
              <a
                key={iv}
                href={itemQuery({ advanced: 'true', interval: iv })}
                className={`btn ${sp.interval === iv ? 'btn-primary' : ''}`}
                style={{ padding: '2px 9px', fontSize: 11 }}
              >
                {iv}
              </a>
            ))}
          </span>
        </div>
        <Candlestick candles={candles} />
      </div>

      </>}
      {/* Top sellers */}
      <div className="card">
        <div className="card-title">
          Historical sellers <span className="sub">not current offers · sorted by sales</span>
          <span className="toolbar-export" style={{ marginLeft: 'auto' }}>
            <CsvButton
              filename={`item-${fileKey}-sellers.csv`}
              headers={showSaleFigures
                ? ['Seller', 'Type', 'Admin shop', 'Sales', 'Quantity', 'Volume']
                : ['Seller', 'Type', 'Admin shop']}
              rows={sellers.map((s) => {
                const base = [
                  s.owner_name ?? (s.admin_shop ? 'Admin shop' : ''),
                  s.shop_account_type ?? (s.admin_shop ? 'ADMIN' : ''),
                  s.admin_shop ? 'yes' : 'no',
                ];
                return showSaleFigures ? [...base, s.sale_count, s.total_quantity, s.total_volume] : base;
              })}
            />
            <JsonButton filename={`item-${fileKey}-sellers.json`} data={sellersExport} />
          </span>
        </div>
        <TableWrap label="Historical sellers">
          <table className="data-table">
            <thead>
              <tr>
                <th>Seller</th>
                <th>Type</th>
                {showSaleFigures && <th className="amount">Sales</th>}
                {showSaleFigures && <th className="amount">Quantity</th>}
                {showSaleFigures && <th className="amount">Volume</th>}
              </tr>
            </thead>
            <tbody>
              {sellers.length === 0 && (
                <tr><td colSpan={showSaleFigures ? 5 : 2} className="empty">No sellers.</td></tr>
              )}
              {sellers.map((s, i) => (
                <tr key={`${s.shop_account_id}-${s.shop_firm_id}-${s.shop_owner_uuid}-${i}`}>
                  <td><span style={{ fontWeight: 500 }}>{s.owner_name ?? (s.admin_shop ? 'Admin shop' : '—')}</span></td>
                  <td>
                    {s.admin_shop ? <span className="badge">admin</span> : null}
                    {s.shop_account_type && <span className={`badge badge-${s.shop_account_type}`}>{s.shop_account_type}</span>}
                  </td>
                  {showSaleFigures && <td className="amount mono">{fmtN(s.sale_count)}</td>}
                  {showSaleFigures && <td className="amount mono">{fmtN(s.total_quantity)}</td>}
                  {showSaleFigures && <td className="amount neutral">{fmtAmtFull(s.total_volume)}</td>}
                </tr>
              ))}
            </tbody>
          </table>
        </TableWrap>
      </div>

      {/* Sales feed — individual transactions (money + quantity per sale) are
          financial drilldown, shown to staff only. */}
      {showSaleFigures && (
      <div className="card">
        <div className="card-title">
          Recent sales <span className="sub">{fmtN(totalSales)} total · newest first</span>
          <span className="toolbar-export" style={{ marginLeft: 'auto' }}>
            <CsvButton
              filename={`item-${fileKey}-sales-page-${sp.page}.csv`}
              headers={['When', 'Direction', 'Quantity', 'Unit price', 'Total', 'Seller', 'World', 'X', 'Y', 'Z']}
              rows={sales.map((s) => [
                s.occurred_at?.toISOString() ?? '',
                s.direction,
                s.quantity,
                s.unit_price,
                s.total_price,
                s.owner_name ?? (s.admin_shop ? 'Admin shop' : ''),
                s.world ?? '',
                s.sign_x,
                s.sign_y,
                s.sign_z,
              ])}
            />
            <JsonButton filename={`item-${fileKey}-sales-page-${sp.page}.json`} data={sales} />
          </span>
        </div>
        <TableWrap label="Recent sales">
          <table className="data-table">
            <thead>
              <tr>
                <th className="ts">When</th>
                <th>Direction</th>
                <th className="amount">Qty</th>
                <th className="amount">Unit price</th>
                <th className="amount">Total</th>
                <th>Seller</th>
                <th>Location</th>
              </tr>
            </thead>
            <tbody>
              {sales.length === 0 && (
                <tr><td colSpan={7} className="empty">No sales.</td></tr>
              )}
              {sales.map((s) => (
                <tr key={s.sale_id}>
                  <td className="ts"><LocalTimestamp value={s.occurred_at} /></td>
                  <td><span className={`badge ${s.direction === 'BUY' ? 'badge-active' : 'badge-archived'}`}>{s.direction}</span></td>
                  <td className="amount mono">{fmtN(s.quantity)}</td>
                  <td className="amount neutral">{fmtAmtFull(s.unit_price)}</td>
                  <td className="amount neutral">{fmtAmtFull(s.total_price)}</td>
                  <td>{s.owner_name ?? (s.admin_shop ? 'Admin shop' : '—')}</td>
                  <td>
                    {(() => {
                      const url = blueMapUrl(mapBase, s.world, s.sign_x, s.sign_y, s.sign_z);
                      const label = `${s.world ?? '—'}${s.sign_x !== null ? ` ${s.sign_x},${s.sign_y},${s.sign_z}` : ''}`;
                      return url ? (
                        <a href={url} target="_blank" rel="noopener noreferrer" className="map-link mono small" title="Open in map">
                          <span className="map-pin" aria-hidden>📍</span><span>{label}</span>
                        </a>
                      ) : (
                        <span className="mono small" style={{ color: 'var(--fg-muted)' }}>{label}</span>
                      );
                    })()}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </TableWrap>

        <Pagination
          page={sp.page}
          totalPages={totalPages}
          totalItems={totalSales}
          basePath={`/chestshop/items/${encodeURIComponent(itemKey)}`}
          searchParams={{ from: returnTo, limit: String(sp.limit) }}
        />
      </div>
      )}

    </>
  );
}

