import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import { parseDaysWindow } from '@/lib/util/daysWindow';
import Link from 'next/link';
import type { Route } from 'next';
import { notFound } from 'next/navigation';
import { getMarketFirm, listFirmMarketItems } from '@/lib/sql/market';
import { fmtAmtFull, fmtN } from '@/lib/format';
import { BackLink } from '@democracycraft/design-system/next';
import { CsvButton } from '@/components/CsvButton';
import { JsonButton } from '@/components/JsonButton';
import { PrivacyGate } from '@/components/PrivacyGate';
import { getViewer } from '@/lib/auth/viewer';
import { canViewFirmFinancials, hasFirmFinancialAccess, hasPermission } from '@/lib/auth/access';
import { auditView } from '@/lib/audit';
import { TableWrap } from '@democracycraft/design-system';

export const dynamic = 'force-dynamic';

export async function generateMetadata({ params }: { params: Promise<{ firmId: string }> }) {
  const { firmId } = await params;
  return buildMetadata({ title: `Firm #${firmId}`, description: 'ChestShop sales and shops for this {server} firm.', path: `/chestshop/firms/${firmId}` });
}

export default async function MarketFirmDetailPage({
  params,
  searchParams,
}: {
  params: Promise<{ firmId: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { firmId: raw } = await params;
  const firmId = Number(raw);
  if (!Number.isInteger(firmId) || firmId <= 0) notFound();

  // `?days=N` — the trailing window the in-game `/firm sales export <firm> <days>`
  // link asks for. Absent/malformed means all-time; oversized values are clamped.
  const days = parseDaysWindow(flattenSearchParams(await searchParams).days);
  const windowLabel = days == null ? 'all-time' : `last ${fmtN(days)} ${days === 1 ? 'day' : 'days'}`;
  const fileSuffix = days == null ? '' : `-${days}d`;

  const viewer = await getViewer();
  const canSeeFinancials = await canViewFirmFinancials(firmId, viewer);

  // Resolve identity for the heading + existence check regardless of access;
  // the financial figures (KPIs + per-item volume) only render when allowed.
  // The window narrows the figures only — a firm with no sales inside it still
  // resolves (zero totals) rather than 404ing.
  const firm = await getMarketFirm(firmId, days);
  if (!firm) notFound();
  const items = canSeeFinancials ? await listFirmMarketItems(firmId, 50, 0, days) : [];

  // Audit privileged inspection: staff viewing a firm's books via elevation
  // (i.e. not one of that firm's own finance-role employees).
  if (canSeeFinancials && hasPermission(viewer, 'firm.financials.read.all')) {
    const financeEmployee = viewer.minecraftUuid
      ? await hasFirmFinancialAccess(firmId, viewer.minecraftUuid)
      : false;
    if (!financeEmployee) {
      await auditView(viewer, { path: `/chestshop/firms/${firmId}`, targetType: 'firm', targetId: String(firmId) });
    }
  }

  return (
    <>
      <BackLink href="/chestshop/firms" label="Market firms" />

      <div className="page-heading">
        <h1>{firm.display_name ?? `Firm #${firm.firm_id}`}</h1>
        <span className="mono" style={{ color: 'var(--fg-muted)', fontSize: 13 }}>#{firm.firm_id}</span>
      </div>

      {!canSeeFinancials ? (
        <PrivacyGate
          kind={viewer.anon ? 'login' : 'private'}
          title="This firm's financials are private"
          hint="Sales, volume, and quantities are only visible to the firm's finance-role employees and to staff. Balances are public; profitability is not."
        />
      ) : (
        <>
          {days != null && (
            <div className="card-title">
              <span className="sub">Showing the {windowLabel}</span>
              <span className="window-tabs">
                <Link href={`/chestshop/firms/${firm.firm_id}` as Route} prefetch={false}>Show all time</Link>
              </span>
            </div>
          )}

          <div className="kpi-grid">
            <div className="kpi">
              <div className="kpi-label">Sales</div>
              <div className="kpi-value">{fmtN(firm.sale_count)}</div>
            </div>
            <div className="kpi">
              <div className="kpi-label">Quantity moved</div>
              <div className="kpi-value">{fmtN(firm.total_quantity)}</div>
            </div>
            <div className="kpi">
              <div className="kpi-label">Volume</div>
              <div className="kpi-value">{fmtAmtFull(firm.total_volume)}</div>
            </div>
            <div className="kpi">
              <div className="kpi-label">Distinct items</div>
              <div className="kpi-value">{fmtN(items.length)}</div>
            </div>
          </div>

          <div className="card">
            <div className="card-title">
              Items sold <span className="sub">{windowLabel}, sorted by trade count</span>
              <span className="toolbar-export" style={{ marginLeft: 'auto' }}>
                <CsvButton
                  filename={`firm-${firm.firm_id}-items${fileSuffix}.csv`}
                  headers={['Item key', 'Item name', 'Material', 'Trades', 'Quantity', 'Volume']}
                  rows={items.map((i) => [
                    i.item_key,
                    i.item_name ?? '',
                    i.material ?? '',
                    i.trade_count,
                    i.total_quantity,
                    i.total_volume,
                  ])}
                />
                <JsonButton filename={`firm-${firm.firm_id}-items${fileSuffix}.json`} data={items} />
              </span>
            </div>
            <TableWrap label="Items sold">
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Item</th>
                    <th>Material</th>
                    <th className="amount">Trades</th>
                    <th className="amount">Quantity</th>
                    <th className="amount">Volume</th>
                  </tr>
                </thead>
                <tbody>
                  {items.length === 0 && (
                    <tr><td colSpan={5} className="empty">{days == null ? 'No items.' : 'No sales in this window.'}</td></tr>
                  )}
                  {items.map((i) => (
                    <tr key={i.item_key}>
                      <td>
                        <Link href={`/chestshop/items/${encodeURIComponent(i.item_key)}` as Route} className="rowlink" prefetch={false}>
                          <span style={{ fontWeight: 500 }}>{i.item_name ?? i.item_key}</span>
                        </Link>
                      </td>
                      <td><span className="mono small" style={{ color: 'var(--fg-muted)' }}>{i.material ?? '—'}</span></td>
                      <td className="amount mono">{fmtN(i.trade_count)}</td>
                      <td className="amount mono">{fmtN(i.total_quantity)}</td>
                      <td className="amount neutral">{fmtAmtFull(i.total_volume)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </TableWrap>
          </div>
        </>
      )}
    </>
  );
}
