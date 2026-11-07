import type { NextRequest } from 'next/server';
import { getMonthlyReport, reportCsv, reportMonths } from '@/lib/services/government';
import { auditGovRead, govScopeOrNull, govSelection } from '@/lib/services/governmentScope';
import { csv, csvResponse } from '../../csv';

// The monthly report as CSV. Internal: the same scope check as the page.
export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  const scope = await govScopeOrNull();
  if (!scope) return new Response('Forbidden', { status: 403 });
  const params = req.nextUrl.searchParams;
  const months = reportMonths(12);
  const requested = `${(params.get('month') ?? '').slice(0, 7)}-01`;
  const month = months.includes(requested) ? requested : months[1] ?? months[0]!;
  const selection = govSelection(scope, params.get('unit') ?? undefined);
  const report = await getMonthlyReport(scope, selection.ids, month);
  await auditGovRead(scope, '/gov/reports/csv', selection.ids);
  return csvResponse(csv(reportCsv(report, selection.label)), `gov-report-${selection.key}-${month.slice(0, 7)}.csv`);
}
