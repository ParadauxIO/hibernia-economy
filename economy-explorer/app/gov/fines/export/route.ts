import type { NextRequest } from 'next/server';
import { parseRange } from '@/lib/range';
import { exportFines } from '@/lib/services/government';
import { auditGovRead, govScopeOrNull, govSelection } from '@/lib/services/governmentScope';
import { parseTableState } from '@/lib/tables/core';
import { finesTable } from '@/lib/tables/fines';
import { csv, csvResponse } from '../../csv';

// Every fine the current filters select, within scope, as CSV (spec §10.3).
// TODO(PAR-540): register this as the `fines` dataset in lib/export/registry.ts
// (lib/export/datasets/gov.ts) once POWER's export registry lands.
export const dynamic = 'force-dynamic';

export async function GET(req: NextRequest) {
  const scope = await govScopeOrNull();
  if (!scope) return new Response('Forbidden', { status: 403 });
  const params = req.nextUrl.searchParams;
  const selection = govSelection(scope, params.get('unit') ?? undefined);
  const range = parseRange(params, { defaultPreset: '30d' });
  const state = parseTableState(params, finesTable);
  const rows = await exportFines(selection.ids, range.range, state);
  await auditGovRead(scope, '/gov/fines/export', selection.ids);
  const body = csv([
    ['Fine ID', 'Issued (UTC)', 'Status', 'Amount', 'Fined player', 'Player UUID', 'Reason', 'Issued by', 'Issuer UUID', 'Receiving account ID', 'Receiving account', 'Transaction ID', 'Revoked (UTC)'],
    ...rows.map((f) => [
      f.fine_id, f.issued_at.toISOString(), f.revoked ? 'revoked' : 'active', f.amount, f.player_name, f.player_uuid, f.reason,
      f.issued_by_name, f.issued_by_uuid, f.gov_account_id, f.gov_account_name, f.txn_id, f.revoked_at?.toISOString() ?? '',
    ]),
  ]);
  return csvResponse(body, `fines-${range.range.from}-${range.range.to}.csv`);
}
