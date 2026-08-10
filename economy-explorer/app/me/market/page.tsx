import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import Link from 'next/link';
import type { Route } from 'next';
import { z } from 'zod';
import { getViewer } from '@/lib/auth/viewer';
import { PrivacyGate } from '@/components/PrivacyGate';
import { listPlayerSales } from '@/lib/sql/market';
import { fmtAmtFull, fmtN } from '@/lib/format';
import { BackLink } from '@/components/BackLink';
import { CsvButton } from '@/components/CsvButton';
import { JsonButton } from '@/components/JsonButton';

export const dynamic = 'force-dynamic';

const ITEM_LIMIT = 15;
const PARTNER_LIMIT = 10;
const WINDOWS = ['7', '30', '90', 'all'] as const;
const SP_SCHEMA = z.object({ window: z.enum(WINDOWS).default('90') });

export async function generateMetadata() {
  return buildMetadata({ title: "My market activity", description: "Your ChestShop trades and market activity on {server}.", path: "/me/market" });
}

export default async function MyMarketPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const viewer = await getViewer();
  if (viewer.anon) {
    return (
      <PrivacyGate kind="login" title="My market activity" hint="Sign in to see your own ChestShop trades." />
    );
  }
  if (!viewer.linked || !viewer.minecraftUuid) {
    return (
      <PrivacyGate kind="link" title="Link your Minecraft account" hint="My market is for linked players only." />
    );
  }

  const sp = SP_SCHEMA.parse(flattenSearchParams(await searchParams));
  const days = sp.window === 'all' ? null : Number(sp.window);
  const windowLabel = sp.window === 'all' ? 'all time' : `last ${sp.window} days`;
  const sales = await listPlayerSales(viewer.minecraftUuid, days);

  interface Item { item_key: string; item_name: string | null; material: string | null; trades: number; qty: number; amount: number }
  interface Partner { owner_name: string; firm_id: number | null; trades: number; amount: number }

  const bought = new Map<string, Item>();
  const sold = new Map<string, Item>();
  const boughtFrom = new Map<string, Partner>();
  const soldTo = new Map<string, Partner>();
  let boughtTrades = 0;
  let soldTrades = 0;
  let totalSpent = 0;
  let totalEarned = 0;

  for (const s of sales) {
    const amount = parseFloat(s.total_price);
    // Whether THIS player bought or sold depends on both their role and the
    // ChestShop direction (which is the customer's perspective):
    //   customer+BUY / owner+SELL → the player bought;
    //   customer+SELL / owner+BUY → the player sold.
    const iBought =
      s.player_role === 'customer' ? s.direction === 'BUY' : s.direction === 'SELL';

    // Counterparty: the shop when the player is the customer, otherwise the
    // customer who traded at the player's shop.
    let partnerKey: string;
    let partnerName: string;
    let partnerFirmId: number | null;
    if (s.player_role === 'customer') {
      partnerFirmId = s.shop_firm_id;
      partnerKey = s.admin_shop
        ? 'admin:shop'
        : s.shop_firm_id != null
        ? `firm:${s.shop_firm_id}`
        : s.shop_owner_uuid
        ? `player:${s.shop_owner_uuid}`
        : 'unknown';
      partnerName = s.owner_name ?? (s.admin_shop ? 'Admin shop' : 'Unknown');
    } else {
      partnerFirmId = null;
      partnerKey = s.customer_uuid ? `cust:${s.customer_uuid}` : 'unknown';
      partnerName = s.customer_name ?? 'Unknown';
    }

    function upsertItem(map: Map<string, Item>) {
      const existing = map.get(s.item_key);
      if (existing) {
        existing.trades++;
        existing.qty += s.quantity;
        existing.amount += amount;
      } else {
        map.set(s.item_key, {
          item_key: s.item_key,
          item_name: s.item_name,
          material: s.material,
          trades: 1,
          qty: s.quantity,
          amount,
        });
      }
    }
    function upsertPartner(map: Map<string, Partner>) {
      const existing = map.get(partnerKey);
      if (existing) {
        existing.trades++;
        existing.amount += amount;
      } else {
        map.set(partnerKey, {
          owner_name: partnerName,
          firm_id: partnerFirmId,
          trades: 1,
          amount,
        });
      }
    }

    if (iBought) {
      boughtTrades++;
      totalSpent += amount;
      upsertItem(bought);
      upsertPartner(boughtFrom);
    } else {
      soldTrades++;
      totalEarned += amount;
      upsertItem(sold);
      upsertPartner(soldTo);
    }
  }

  const topBought = [...bought.values()].sort((a, b) => b.amount - a.amount).slice(0, ITEM_LIMIT);
  const topSold = [...sold.values()].sort((a, b) => b.amount - a.amount).slice(0, ITEM_LIMIT);
  const topBoughtFrom = [...boughtFrom.values()].sort((a, b) => b.amount - a.amount).slice(0, PARTNER_LIMIT);
  const topSoldTo = [...soldTo.values()].sort((a, b) => b.amount - a.amount).slice(0, PARTNER_LIMIT);

  return (
    <>
      <BackLink href="/me" label="My data" />

      <div className="page-heading">
        <h1>My market</h1>
        <span className="sub">your ChestShop trades · {windowLabel}</span>
        <span className="window-tabs">
          {WINDOWS.map((w) => (
            <a key={w} href={`/me/market?window=${w}`} className={w === sp.window ? 'active' : ''}>
              {w === 'all' ? 'All' : `${w}d`}
            </a>
          ))}
        </span>
      </div>

      <div className="kpi-grid">
        <div className="kpi">
          <div className="kpi-label">Spent</div>
          <div className="kpi-value">{fmtAmtFull(totalSpent)}</div>
          <div className="kpi-meta">{fmtN(boughtTrades)} buys</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Earned</div>
          <div className="kpi-value">{fmtAmtFull(totalEarned)}</div>
          <div className="kpi-meta">{fmtN(soldTrades)} sales</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Net</div>
          <div className={`kpi-value ${totalEarned - totalSpent >= 0 ? 'pos' : 'neg'}`}>
            {totalEarned - totalSpent >= 0 ? '+' : ''}{fmtAmtFull(totalEarned - totalSpent)}
          </div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Trades</div>
          <div className="kpi-value">{fmtN(sales.length)}</div>
        </div>
      </div>

      {/* Helpers below render the per-table export controls. */}
      {/* Items you bought */}
      <div className="card">
        <div className="card-title">
          Items you buy <span className="sub">top {topBought.length} by spend</span>
          <ItemExport items={topBought} basename="my-items-bought" spendLabel="Spent" />
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Item</th>
                <th className="amount">Trades</th>
                <th className="amount">Quantity</th>
                <th className="amount">Spent</th>
              </tr>
            </thead>
            <tbody>
              {topBought.length === 0 && (
                <tr><td colSpan={4} className="empty">No buys in this window.</td></tr>
              )}
              {topBought.map((i) => (
                <tr key={i.item_key}>
                  <td>
                    <Link href={`/chestshop/items/${encodeURIComponent(i.item_key)}` as Route} className="rowlink" prefetch={false}>
                      <span style={{ fontWeight: 500 }}>{i.item_name ?? i.item_key}</span>
                      <span className="mono muted small">{' ' + (i.material ?? '')}</span>
                    </Link>
                  </td>
                  <td className="amount mono">{fmtN(i.trades)}</td>
                  <td className="amount mono">{fmtN(i.qty)}</td>
                  <td className="amount neutral">{fmtAmtFull(i.amount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Items you sold */}
      <div className="card">
        <div className="card-title">
          Items you sell <span className="sub">top {topSold.length} by earnings</span>
          <ItemExport items={topSold} basename="my-items-sold" spendLabel="Earned" />
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Item</th>
                <th className="amount">Trades</th>
                <th className="amount">Quantity</th>
                <th className="amount">Earned</th>
              </tr>
            </thead>
            <tbody>
              {topSold.length === 0 && (
                <tr><td colSpan={4} className="empty">No sales in this window.</td></tr>
              )}
              {topSold.map((i) => (
                <tr key={i.item_key}>
                  <td>
                    <Link href={`/chestshop/items/${encodeURIComponent(i.item_key)}` as Route} className="rowlink" prefetch={false}>
                      <span style={{ fontWeight: 500 }}>{i.item_name ?? i.item_key}</span>
                      <span className="mono muted small">{' ' + (i.material ?? '')}</span>
                    </Link>
                  </td>
                  <td className="amount mono">{fmtN(i.trades)}</td>
                  <td className="amount mono">{fmtN(i.qty)}</td>
                  <td className="amount neutral">{fmtAmtFull(i.amount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Who you buy from */}
      <div className="card">
        <div className="card-title">
          Who you buy from <span className="sub">top {topBoughtFrom.length}</span>
          <PartnerExport partners={topBoughtFrom} basename="my-buy-from" who="Seller" amountLabel="Spent" />
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Seller</th>
                <th className="amount">Trades</th>
                <th className="amount">Spent</th>
              </tr>
            </thead>
            <tbody>
              {topBoughtFrom.length === 0 && (
                <tr><td colSpan={3} className="empty">—</td></tr>
              )}
              {topBoughtFrom.map((p, i) => (
                <tr key={`${p.firm_id}-${p.owner_name}-${i}`}>
                  <td>
                    {p.firm_id ? (
                      <Link href={`/chestshop/firms/${p.firm_id}` as Route} className="rowlink" prefetch={false}>
                        {p.owner_name}
                      </Link>
                    ) : p.owner_name}
                  </td>
                  <td className="amount mono">{fmtN(p.trades)}</td>
                  <td className="amount neutral">{fmtAmtFull(p.amount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Who buys from you */}
      <div className="card">
        <div className="card-title">
          Who buys from you <span className="sub">top {topSoldTo.length}</span>
          <PartnerExport partners={topSoldTo} basename="my-sell-to" who="Customer" amountLabel="Earned" />
        </div>
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Customer</th>
                <th className="amount">Trades</th>
                <th className="amount">Earned</th>
              </tr>
            </thead>
            <tbody>
              {topSoldTo.length === 0 && (
                <tr><td colSpan={3} className="empty">—</td></tr>
              )}
              {topSoldTo.map((p, i) => (
                <tr key={`${p.firm_id}-${p.owner_name}-${i}`}>
                  <td>{p.owner_name}</td>
                  <td className="amount mono">{fmtN(p.trades)}</td>
                  <td className="amount neutral">{fmtAmtFull(p.amount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}


// Per-table export controls. Local to this page — the bought/sold and
// buy-from/sell-to tables share two shapes, so two small wrappers keep the
// four card titles tidy while still emitting the same CsvButton/JsonButton.
function ItemExport({
  items,
  basename,
  spendLabel,
}: {
  items: { item_key: string; item_name: string | null; material: string | null; trades: number; qty: number; amount: number }[];
  basename: string;
  spendLabel: string;
}) {
  return (
    <span className="toolbar-export" style={{ marginLeft: 'auto' }}>
      <CsvButton
        filename={`${basename}.csv`}
        headers={['Item key', 'Item name', 'Material', 'Trades', 'Quantity', spendLabel]}
        rows={items.map((i) => [i.item_key, i.item_name ?? '', i.material ?? '', i.trades, i.qty, i.amount])}
      />
      <JsonButton filename={`${basename}.json`} data={items} />
    </span>
  );
}

function PartnerExport({
  partners,
  basename,
  who,
  amountLabel,
}: {
  partners: { owner_name: string; firm_id: number | null; trades: number; amount: number }[];
  basename: string;
  who: string;
  amountLabel: string;
}) {
  return (
    <span className="toolbar-export" style={{ marginLeft: 'auto' }}>
      <CsvButton
        filename={`${basename}.csv`}
        headers={[who, 'Firm ID', 'Trades', amountLabel]}
        rows={partners.map((p) => [p.owner_name, p.firm_id ?? '', p.trades, p.amount])}
      />
      <JsonButton filename={`${basename}.json`} data={partners} />
    </span>
  );
}
