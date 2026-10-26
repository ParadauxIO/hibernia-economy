// A firm's monthly statements (12 months, by category) as CSV or JSON. Same
// gate as the Financials tab. TODO(POWER PAR-540): also register a
// `firm-transactions` dataset with the export registry once it exists.
import { canViewFirmFinancials, findFirmByDisplayName } from '@/lib/auth/access';
import { getViewer } from '@/lib/auth/viewer';
import { toCsv } from '@/lib/entityExport';
import { listFirmAccounts } from '@/lib/sql/firm';
import { monthlyStatements } from '@/lib/services/firm';

export const dynamic = 'force-dynamic';

export async function GET(request: Request, { params }: { params: Promise<{ firmName: string }> }): Promise<Response> {
  const { firmName } = await params;
  const [firm, viewer] = await Promise.all([findFirmByDisplayName(decodeURIComponent(firmName)), getViewer()]);
  if (!firm) return new Response('Not found', { status: 404 });
  if (!(await canViewFirmFinancials(firm.firm_id, viewer))) return new Response(viewer.anon ? 'Log in to export' : 'Forbidden', { status: viewer.anon ? 401 : 403 });
  const accounts = await listFirmAccounts(firm.firm_id);
  const { statements } = await monthlyStatements(accounts.map((a) => a.account_id));
  const rows = statements.flatMap((s) => [
    ...s.revenue.map((l) => ({ month: s.month, section: 'revenue', category: l.label, amount: l.amount })),
    ...s.costs.map((l) => ({ month: s.month, section: 'costs', category: l.label, amount: `-${l.amount}` })),
    { month: s.month, section: 'net', category: 'Net', amount: s.net },
  ]);
  const slug = firm.display_name.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '').toLowerCase() || `firm-${firm.firm_id}`;
  const headers = { 'Cache-Control': 'private, no-store' };
  if (new URL(request.url).searchParams.get('format') === 'json') {
    return Response.json(statements, { headers: { ...headers, 'Content-Disposition': `attachment; filename="${slug}-statements.json"` } });
  }
  return new Response(toCsv(rows, ['month', 'section', 'category', 'amount']), {
    headers: { ...headers, 'Content-Type': 'text/csv; charset=utf-8', 'Content-Disposition': `attachment; filename="${slug}-statements.csv"` },
  });
}
