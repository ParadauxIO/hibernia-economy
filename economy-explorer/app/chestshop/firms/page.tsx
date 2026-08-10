import { buildMetadata } from '@/lib/metadata';
import { flattenSearchParams } from '@/lib/util/searchParams';
import Link from 'next/link';
import type { Route } from 'next';
import { z } from 'zod';
import { listMarketFirms, countMarketFirms } from '@/lib/sql/market';
import { fmtAmtFull, fmtN } from '@/lib/format';
import { Pagination } from '@/components/Pagination';
import { SectionTabs } from '@/components/SectionTabs';
import { CsvButton } from '@/components/CsvButton';
import { JsonButton } from '@/components/JsonButton';
import { getViewer } from '@/lib/auth/viewer';
import { isStaff } from '@/lib/auth/access';

const SP_SCHEMA = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(200).default(50),
});

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: "ChestShop Firms", description: "Firms running ChestShops on {server}.", path: "/chestshop/firms" });
}

export default async function ChestShopFirmsPage({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const sp = SP_SCHEMA.parse(flattenSearchParams(await searchParams));
  const [viewer, firms, total] = await Promise.all([
    getViewer(),
    listMarketFirms(sp.limit, (sp.page - 1) * sp.limit),
    countMarketFirms(),
  ]);
  const totalPages = Math.max(1, Math.ceil(total / sp.limit));
  // Per-firm sales/quantity/volume is financial drilldown across many firms;
  // no single firm employee could have access to all of them, so the figures
  // are staff-only. Everyone still gets the firm directory (names + ranking).
  const showFigures = isStaff(viewer);

  // Sales/quantity/volume are staff-only financial drilldown — whitelist the
  // public fields for the JSON export when the viewer can't see figures.
  const firmsExport = showFigures
    ? firms
    : firms.map((f) => ({ firm_id: f.firm_id, display_name: f.display_name }));

  return (
    <>
      <div className="page-heading">
        <h1>Market firms</h1>
        <span className="sub">{fmtN(total)} firms with ChestShop activity</span>
        <SectionTabs />
      </div>

      {!viewer.anon && (
      <div className="toolbar">
        <span className="toolbar-export" style={{ marginLeft: 'auto' }}>
          <CsvButton
            filename={`market-firms-page-${sp.page}.csv`}
            headers={showFigures ? ['Firm ID', 'Name', 'Sales', 'Quantity', 'Volume'] : ['Firm ID', 'Name']}
            rows={firms.map((f) => {
              const base = [f.firm_id, f.display_name ?? `Firm #${f.firm_id}`];
              return showFigures ? [...base, f.sale_count, f.total_quantity, f.total_volume] : base;
            })}
          />
          <JsonButton filename={`market-firms-page-${sp.page}.json`} data={firmsExport} />
        </span>
      </div>
      )}

      <div className="table-wrap">
        <table className="data-table">
          <thead>
            <tr>
              <th>Firm</th>
              {showFigures && <th className="amount">Sales</th>}
              {showFigures && <th className="amount">Quantity</th>}
              {showFigures && <th className="amount">Volume</th>}
            </tr>
          </thead>
          <tbody>
            {firms.length === 0 && (
              <tr><td colSpan={showFigures ? 4 : 1} className="empty">No firms.</td></tr>
            )}
            {firms.map((f) => (
              <tr key={f.firm_id}>
                <td>
                  <Link href={`/chestshop/firms/${f.firm_id}` as Route} className="rowlink" prefetch={false}>
                    <span style={{ fontWeight: 500 }}>{f.display_name ?? `Firm #${f.firm_id}`}</span>
                    <span className="mono muted small">{` #${f.firm_id}`}</span>
                  </Link>
                </td>
                {showFigures && <td className="amount mono">{fmtN(f.sale_count)}</td>}
                {showFigures && <td className="amount mono">{fmtN(f.total_quantity)}</td>}
                {showFigures && <td className="amount neutral">{fmtAmtFull(f.total_volume)}</td>}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <Pagination
        page={sp.page}
        totalPages={totalPages}
        totalItems={total}
        basePath="/chestshop/firms"
        searchParams={{ limit: String(sp.limit) }}
      />
    </>
  );
}

