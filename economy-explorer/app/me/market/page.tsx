import Link from 'next/link';
import type { Route } from 'next';
import { MetricCard, MetricRow, Money, Num, PageHeader, Section, TableWrap } from '@democracycraft/design-system';
import { RangePicker } from '@democracycraft/design-system/next';
import { CsvButton } from '@/components/CsvButton';
import { getViewer } from '@/lib/auth/viewer';
import { buildMetadata } from '@/lib/metadata';
import { parseRange, toDay } from '@/lib/range';
import { meGate } from '@/lib/services/me';
import { getMyMarket, ITEM_LIMIT, PARTNER_LIMIT } from '@/lib/services/meMarket';
import type { MarketItemRow, MarketPartnerRow } from '@/lib/sql/me';
import { MeGateView } from '../_components/MeGateView';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'My market activity', description: 'Your ChestShop trades and market activity on {server}.', path: '/me/market' });
}

/** Old links used ?window=7|30|90|all. */
const LEGACY_WINDOW: Record<string, string> = { '7': '7d', '30': '30d', '90': '90d', all: 'all' };

export default async function MyMarketPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const viewer = await getViewer();
  const gate = meGate(viewer);
  if (!gate.ok) return <MeGateView kind={gate.kind} title="My market activity" />;
  const sp = { ...(await searchParams) };
  if (!sp.range && typeof sp.window === 'string' && LEGACY_WINDOW[sp.window]) sp.range = LEGACY_WINDOW[sp.window];
  const range = parseRange(sp, { defaultPreset: '90d' });
  const since = range.preset === 'all' ? null : new Date(`${range.range.from}T00:00:00Z`);
  const m = await getMyMarket(gate.uuid, { since });

  return <>
    <PageHeader title="Market" subtitle="Your ChestShop trades: what you buy at other shops and what sells at your own. Firm shops are on the firm’s pages." />
    <RangePicker value={range.value} allowCompare={false} presets={['7d', '30d', '90d', '1y', 'all']} max={toDay(new Date())} />
    <MetricRow label="Market summary">
      <MetricCard label="Spent" value={<Money value={m.spent} />} meta={<><Num value={m.buys} /> buys</>} />
      <MetricCard label="Earned" value={<Money value={m.earned} />} meta={<><Num value={m.sales} /> sales</>} />
      <MetricCard label="Net" value={<Money value={m.net} sign="exceptZero" tone="auto" />} />
      <MetricCard label="Trades" value={<Num value={m.buys + m.sales} />} meta={range.label} />
    </MetricRow>

    <Section title="Items" description={`${range.label}. Top ${ITEM_LIMIT} each way by money.`}>
      <div className="me-split">
        <ItemTable title="Items you buy" rows={m.boughtItems} amountLabel="Spent" file="my-items-bought" />
        <ItemTable title="Items you sell" rows={m.soldItems} amountLabel="Earned" file="my-items-sold" />
      </div>
    </Section>

    <Section title="Trading partners" divider description={`${range.label}. Top ${PARTNER_LIMIT} each way.`}>
      <div className="me-split">
        <PartnerTable title="Who you buy from" rows={m.boughtFrom} who="Seller" amountLabel="Spent" file="my-buy-from" />
        <PartnerTable title="Who buys from you" rows={m.soldTo} who="Customer" amountLabel="Earned" file="my-sell-to" />
      </div>
    </Section>
  </>;
}

function ItemTable({ title, rows, amountLabel, file }: { title: string; rows: MarketItemRow[]; amountLabel: string; file: string }) {
  return <div>
    <div className="me-group-head">
      <h3 className="me-subhead">{title}</h3>
      <span className="toolbar-export"><CsvButton filename={`${file}.csv`} headers={['Item key', 'Item', 'Material', 'Trades', 'Quantity', amountLabel]}
        rows={rows.map((i) => [i.item_key, i.item_name ?? '', i.material ?? '', i.trades, i.quantity, i.amount])} /></span>
    </div>
    <TableWrap label={title}>
      <table className="data-table">
        <thead><tr><th scope="col">Item</th><th scope="col" className="amount">Trades</th><th scope="col" className="amount">Quantity</th><th scope="col" className="amount">{amountLabel}</th></tr></thead>
        <tbody>
          {rows.length === 0 && <tr><td colSpan={4} className="me-empty">None in this period.</td></tr>}
          {rows.map((i) => <tr key={i.item_key}>
            <td><Link href={`/chestshop/items/${encodeURIComponent(i.item_key)}` as Route} prefetch={false}>{i.item_name ?? i.item_key}</Link></td>
            <td className="amount"><Num value={i.trades} /></td>
            <td className="amount"><Num value={i.quantity} /></td>
            <td className="amount"><Money value={i.amount} /></td>
          </tr>)}
        </tbody>
      </table>
    </TableWrap>
  </div>;
}

function partnerName(p: MarketPartnerRow): string {
  if (p.partner_kind === 'admin') return 'Server shop';
  if (p.partner_kind === 'firm') return p.firm_name ?? `Firm #${p.firm_id}`;
  return p.player_name ?? 'Unknown player';
}

function PartnerTable({ title, rows, who, amountLabel, file }: { title: string; rows: MarketPartnerRow[]; who: string; amountLabel: string; file: string }) {
  return <div>
    <div className="me-group-head">
      <h3 className="me-subhead">{title}</h3>
      <span className="toolbar-export"><CsvButton filename={`${file}.csv`} headers={[who, 'Firm ID', 'Trades', amountLabel]}
        rows={rows.map((p) => [partnerName(p), p.firm_id ?? '', p.trades, p.amount])} /></span>
    </div>
    <TableWrap label={title}>
      <table className="data-table">
        <thead><tr><th scope="col">{who}</th><th scope="col" className="amount">Trades</th><th scope="col" className="amount">{amountLabel}</th></tr></thead>
        <tbody>
          {rows.length === 0 && <tr><td colSpan={3} className="me-empty">None in this period.</td></tr>}
          {rows.map((p, i) => <tr key={`${p.partner_kind}-${p.firm_id ?? p.player_uuid ?? i}`}>
            <td>{p.partner_kind === 'firm' && p.firm_id !== null
              ? <Link href={`/chestshop/firms/${p.firm_id}` as Route} prefetch={false}>{partnerName(p)}</Link>
              : partnerName(p)}</td>
            <td className="amount"><Num value={p.trades} /></td>
            <td className="amount"><Money value={p.amount} /></td>
          </tr>)}
        </tbody>
      </table>
    </TableWrap>
  </div>;
}
