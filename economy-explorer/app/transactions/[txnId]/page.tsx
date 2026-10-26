import { notFound } from 'next/navigation';
import { PageHeader, When } from '@democracycraft/design-system';
import { BackLink } from '@democracycraft/design-system/next';
import { PrivacyGate } from '@/components/PrivacyGate';
import { auditView } from '@/lib/audit';
import { canViewTransaction, hasPermission, readableAccountIds } from '@/lib/auth';
import { getViewer } from '@/lib/auth/viewer';
import { buildMetadata } from '@/lib/metadata';
import { DEFAULT_FOLLOW_HOURS, FOLLOW_HOURS, followTheMoney, getTransactionDetail } from '@/lib/services/transactions';
import { TxnDetailBody } from '../TxnDetailBody';

export const dynamic = 'force-dynamic';

export async function generateMetadata({ params }: { params: Promise<{ txnId: string }> }) {
  const { txnId } = await params;
  return buildMetadata({ title: `Transaction #${txnId}`, description: 'Details of a single {server} ledger transaction.', path: `/transactions/${txnId}` });
}

export default async function TransactionDetailPage({ params, searchParams }: {
  params: Promise<{ txnId: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const viewer = await getViewer();
  if (viewer.anon) {
    return <PrivacyGate kind="login" title="Transaction detail is private" hint="Sign in to view transactions involving accounts you can access." />;
  }
  const { txnId: idStr } = await params;
  const id = Number(idStr);
  if (!Number.isSafeInteger(id) || id <= 0) notFound();
  // transactions.read, or read access to an account the transaction touches.
  if (!await canViewTransaction(id, viewer)) {
    return <PrivacyGate kind="private" title="Transaction access required" hint="You need read access to an account involved in this transaction, or permission to investigate all transactions." />;
  }

  const detail = await getTransactionDetail(id);
  if (!detail) notFound();

  // Record both global investigations and relationship-based transaction reads.
  await auditView(viewer, { path: `/transactions/${id}`, targetType: 'transaction', targetId: String(id) });

  const investigator = hasPermission(viewer, 'transactions.read');
  const raw = (await searchParams).follow;
  const requested = Number(Array.isArray(raw) ? raw[0] : raw);
  const hours = (FOLLOW_HOURS as readonly number[]).includes(requested) ? requested : DEFAULT_FOLLOW_HOURS;
  // Following the money shows other transactions on the receiving accounts, so a
  // participant only follows accounts they can read themselves.
  const canFollow = async (ids: number[]) => (investigator ? new Set(ids) : readableAccountIds(viewer, ids));
  const followed = await followTheMoney(detail, hours, canFollow);

  return (
    <>
      {investigator ? <BackLink href="/transactions" label="Transactions" /> : <BackLink href="/me" label="My data" />}
      <PageHeader
        eyebrow={detail.txn.plugin_system ?? 'Ledger'}
        title={`Transaction #${detail.txn.txn_id}`}
        subtitle={detail.txn.message || undefined}
        meta={<>Settled <When value={detail.txn.settlement_time} time relative={false} /></>}
      />
      <TxnDetailBody
        detail={detail}
        followed={followed}
        hours={hours}
        followHref={(h) => (h === DEFAULT_FOLLOW_HOURS ? `/transactions/${id}` : `/transactions/${id}?follow=${h}`)}
        txnHref={(txn) => `/transactions/${txn}${hours === DEFAULT_FOLLOW_HOURS ? '' : `?follow=${hours}`}`}
        showFollow={investigator || followed.length > 0}
      />
    </>
  );
}
