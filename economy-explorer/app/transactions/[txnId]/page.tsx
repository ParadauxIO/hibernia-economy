import { canViewTransaction, hasPermission } from '@/lib/auth';
import { buildMetadata } from '@/lib/metadata';
import Link from 'next/link';
import type { Route } from 'next';
import { notFound } from 'next/navigation';
import { findTransaction, findPostingsByTxnId } from '@/lib/sql/ledger';
import { accountLabel, fmtAmtFull } from '@/lib/format';
import { LocalTimestamp } from '@/components/LocalTimestamp';
import { getViewer } from '@/lib/auth/viewer';
import { auditView } from '@/lib/audit';
import { BackLink } from '@/components/BackLink';
import { Player } from '@/components/Player';
import { PrivacyGate } from '@/components/PrivacyGate';
import { TableWrap } from '@/components/TableWrap';

export const dynamic = 'force-dynamic';

export async function generateMetadata({ params }: { params: Promise<{ txnId: string }> }) {
  const { txnId } = await params;
  return buildMetadata({ title: `Transaction #${txnId}`, description: 'Details of a single {server} ledger transaction.', path: `/transactions/${txnId}` });
}

export default async function TransactionDetailPage({
  params,
}: {
  params: Promise<{ txnId: string }>;
}) {
  const viewer = await getViewer();
  if (viewer.anon) {
    return (
      <PrivacyGate
        kind="login"
        title="Transaction detail is private"
        hint="Sign in to view transactions involving accounts you can access."
      />
    );
  }
  const { txnId: idStr } = await params;
  const id = Number(idStr);
  if (!Number.isSafeInteger(id) || id <= 0) notFound();
  if (!await canViewTransaction(id, viewer)) {
    return <PrivacyGate kind="private" title="Transaction access required" hint="You need read access to an account involved in this transaction, or permission to investigate all transactions." />;
  }

  const [txn, postings] = await Promise.all([findTransaction(id), findPostingsByTxnId(id)]);
  if (!txn) notFound();

  // Record both global investigations and relationship-based transaction reads.
  await auditView(viewer, { path: `/transactions/${id}`, targetType: 'transaction', targetId: String(id) });

  return (
    <>
      {hasPermission(viewer, 'transactions.read')
        ? <BackLink href="/transactions" label="Transactions" />
        : <BackLink href="/me" label="My data" />}

      <div className="page-heading">
        <h1>
          Txn <span className="mono">#{txn.txn_id}</span>
        </h1>
      </div>

      <div className="kpi-grid">
        <div className="kpi">
          <div className="kpi-label">Settled</div>
          <div className="kpi-value" style={{ fontSize: 16, paddingTop: 6 }}><LocalTimestamp value={txn.settlement_time} /></div>
          {txn.trade_time && <div className="kpi-meta">trade: <LocalTimestamp value={txn.trade_time} /></div>}
        </div>
        <div className="kpi">
          <div className="kpi-label">Initiator</div>
          <div className="kpi-value" style={{ fontSize: 18, paddingTop: 6 }}>
            <Player name={txn.initiator_name} uuid={txn.initiator_uuid} />
          </div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Source</div>
          <div className="kpi-value mono" style={{ fontSize: 16, paddingTop: 6 }}>{txn.plugin_system ?? '—'}</div>
        </div>
        <div className="kpi">
          <div className="kpi-label">Postings</div>
          <div className="kpi-value">{postings.length}</div>
        </div>
      </div>

      {txn.message && (
        <div className="card">
          <div className="card-title">Message</div>
          <div style={{ padding: '4px 4px 8px', color: 'var(--fg)' }}>{txn.message}</div>
        </div>
      )}

      <div className="card">
        <div className="card-title">Postings</div>
        <TableWrap label="Postings"><table className="data-table">
          <thead>
            <tr>
              <th>Posting</th>
              <th>Account</th>
              <th className="amount">Amount</th>
              <th>Memo</th>
            </tr>
          </thead>
          <tbody>
            {postings.map((p) => {
              const v = parseFloat(p.amount);
              const cls = v > 0 ? 'amount pos' : v < 0 ? 'amount neg' : 'amount neutral';
              return (
                <tr key={p.posting_id}>
                  <td><span className="mono">#{p.posting_id}</span></td>
                  <td>
                    <Link href={`/accounts/${p.account_id}` as Route} className="rowlink" prefetch={false}>
                      <span style={{ fontWeight: 500 }}>{accountLabel(p)}</span>
                      <span className="mono muted small">{` #${p.account_id}`}</span>
                    </Link>
                  </td>
                  <td className={cls}>{v > 0 ? '+' : ''}{fmtAmtFull(p.amount)}</td>
                  <td>
                    <span className="mono" style={{ color: 'var(--fg-muted)' }}>{p.memo ?? '—'}</span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table></TableWrap>
      </div>
    </>
  );
}
