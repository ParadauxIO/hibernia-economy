import Link from 'next/link';
import { Money, PageHeader } from '@democracycraft/design-system';
import { DataTable, TablePager, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import { hasPermission } from '@/lib/auth/access';
import { getViewer } from '@/lib/auth/viewer';
import { buildMetadata } from '@/lib/metadata';
import { getMarketFirms } from '@/lib/services/market';
import type { MarketFirmRow } from '@/lib/sql/market';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: 'Market firms', description: 'Firms running shops on {server}, ranked by trading.', path: '/chestshop/firms' });
}

const PAGE_SIZE = 50;
const RouterLink: LinkComponent = Link;

/**
 * Firms with ChestShop activity, ranked by all-time volume. The ranking is
 * public; per-firm sales figures are financial drilldown, so only viewers with
 * market.figures.read see the numbers.
 */
export default async function MarketFirmsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  const page = Math.max(1, Math.min(10_000, Number.parseInt(typeof sp.page === 'string' ? sp.page : '1', 10) || 1));
  const [viewer, { firms, total }] = await Promise.all([getViewer(), getMarketFirms(PAGE_SIZE, (page - 1) * PAGE_SIZE)]);
  const figures = hasPermission(viewer, 'market.figures.read');
  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const columns: Column<MarketFirmRow>[] = [
    { key: 'rank', header: '#', numeric: true, cell: (_f, i) => ((page - 1) * PAGE_SIZE + i + 1).toLocaleString('en-US') },
    { key: 'firm', header: 'Firm', cell: (f) => <>{f.display_name ?? `Firm #${f.firm_id}`}<span className="dt-sub">{` #${f.firm_id}`}</span></> },
    ...(figures ? [
      { key: 'sales', header: 'Sales', numeric: true, secondary: true, cell: (f: MarketFirmRow) => f.sale_count.toLocaleString('en-US') },
      { key: 'qty', header: 'Items', numeric: true, secondary: true, cell: (f: MarketFirmRow) => f.total_quantity.toLocaleString('en-US') },
      { key: 'volume', header: 'Volume', numeric: true, cell: (f: MarketFirmRow) => <Money value={f.total_volume} /> },
    ] : []),
  ];
  return <>
    <PageHeader title="Market firms" subtitle={`${total.toLocaleString('en-US')} firms have run shops, ranked by how much they have traded.${figures ? '' : ' Sales figures are visible to staff.'}`} />
    <DataTable id="market-firms" label="Market firms" columns={columns} rows={firms} rowKey={(f) => f.firm_id}
      rowHref={(f) => `/chestshop/firms/${f.firm_id}`} link={RouterLink} empty={<p className="dt-empty-text">No firms have traded yet.</p>} />
    <TablePager page={Math.min(page, totalPages)} totalPages={totalPages} hrefFor={(p) => (p > 1 ? `/chestshop/firms?page=${p}` : '/chestshop/firms')} link={RouterLink} />
  </>;
}
