// The current page of an account's history as CSV or JSON (the ExportMenu's
// "This page"). Same gate as the page; full-history exports are POWER's export
// registry (TODO(POWER PAR-540): register an `account-transactions` dataset).
import { auditRelationshipRead } from '@/lib/audit';
import { canViewAccount, findAccount } from '@/lib/auth/access';
import { getViewer } from '@/lib/auth/viewer';
import { getAccountContext, getTransactionsPage } from '@/lib/services/account';
import { accountTxnsTable } from '@/lib/tables/accountTxns';
import { parseTableState } from '@/lib/tables/core';
import { toCsv } from '@/lib/entityExport';

export const dynamic = 'force-dynamic';

export async function GET(request: Request, { params }: { params: Promise<{ accountId: string }> }): Promise<Response> {
  const { accountId } = await params;
  if (!/^[1-9]\d{0,9}$/.test(accountId)) return new Response('Not found', { status: 404 });
  const id = Number(accountId);
  const [viewer, account] = await Promise.all([getViewer(), findAccount(id)]);
  if (!account) return new Response('Not found', { status: 404 });
  if (!(await canViewAccount(id, viewer))) return new Response(viewer.anon ? 'Log in to export this account' : 'Forbidden', { status: viewer.anon ? 401 : 403 });
  if (account.owner_uuid !== viewer.minecraftUuid) {
    await auditRelationshipRead(viewer, { accountId: id, accountType: account.account_type }, `/accounts/${id}/export`);
  }

  const url = new URL(request.url);
  const state = parseTableState(url.searchParams, accountTxnsTable);
  const context = await getAccountContext(account);
  const page = await getTransactionsPage(account, context.firm?.firm_id ?? null, state);
  const rows = page.rows.map((t) => ({
    txn_id: t.txnId,
    settled_at: t.at.toISOString(),
    amount: t.amount,
    balance_after: t.balanceAfter ?? '',
    category: t.categoryLabel,
    counterparty_account_id: t.counterparty?.accountId ?? '',
    counterparty: t.counterparty?.name ?? '',
    counterparty_type: t.counterparty?.accountType ?? '',
    message: t.message ?? '',
    memo: t.memo ?? '',
    initiator: t.initiator?.name ?? t.initiator?.uuid ?? '',
  }));
  const name = `account-${id}-transactions`;
  const headers = { 'Cache-Control': 'private, no-store' };
  if (url.searchParams.get('format') === 'json') {
    return Response.json(rows, { headers: { ...headers, 'Content-Disposition': `attachment; filename="${name}.json"` } });
  }
  return new Response(toCsv(rows), {
    headers: { ...headers, 'Content-Type': 'text/csv; charset=utf-8', 'Content-Disposition': `attachment; filename="${name}.csv"` },
  });
}
