// A transaction's facts, postings and "follow the money", shared by the drawer
// on /transactions and the full page /transactions/[id]. Server component.

import Link from 'next/link';
import { DescriptionList, Money, Section, Segmented, When } from '@democracycraft/design-system';
import { DataTable, type Column, type LinkComponent } from '@democracycraft/design-system/table';
import { Player } from '@/components/Player';
import { accountLabel } from '@/lib/format';
import { FOLLOW_HOURS, type FollowedAccount, type TransactionDetail } from '@/lib/services/transactions';
import type { TxnPostingRow } from '@/lib/sql/ledger';
import styles from './txn.module.css';

const RouterLink: LinkComponent = Link;

const postingColumns: Column<TxnPostingRow>[] = [
  {
    key: 'account',
    header: 'Account',
    cell: (p) => <><span>{accountLabel(p)}</span><span className="dt-sub">{` #${p.account_id}`}</span></>,
  },
  { key: 'amount', header: 'Amount', numeric: true, cell: (p) => <Money value={p.amount} sign="exceptZero" tone="auto" /> },
  { key: 'memo', header: 'Memo', secondary: true, cell: (p) => p.memo ?? <span className="muted">—</span> },
];

interface MovementRow {
  txn_id: number;
  settlement_time: Date;
  message: string | null;
  plugin_system: string | null;
  amount: string;
  current: boolean;
}

function movementColumns(): Column<MovementRow>[] {
  return [
    {
      key: 'what',
      header: 'Transaction',
      cell: (m) => m.current
        ? <><strong>This transaction</strong><span className="dt-sub">{` #${m.txn_id}`}</span></>
        : <><span>{m.message || 'No message'}</span><span className="dt-sub">{` #${m.txn_id}`}</span></>,
    },
    { key: 'amount', header: 'On this account', numeric: true, cell: (m) => <Money value={m.amount} sign="exceptZero" tone="auto" /> },
    { key: 'when', header: 'Settled', align: 'end', cell: (m) => <When value={m.settlement_time} time relative={false} /> },
  ];
}

export function TxnDetailBody({ detail, followed, hours, followHref, txnHref, showFollow, inDrawer = false }: {
  detail: TransactionDetail;
  followed: FollowedAccount[];
  hours: number;
  /** The same view with another follow window. */
  followHref: (hours: number) => string;
  /** Where a transaction in the follow lists leads. */
  txnHref: (txnId: number) => string;
  /** False when the viewer may not see any receiving account's history. */
  showFollow: boolean;
  inDrawer?: boolean;
}) {
  const { txn, postings } = detail;
  return (
    <div className={inDrawer ? `${styles.body} ${styles.drawer}` : styles.body}>
      <DescriptionList
        columns={2}
        items={[
          { label: 'Amount', value: <Money value={detail.volume} /> },
          { label: 'Settled', value: <When value={txn.settlement_time} time relative={false} /> },
          { label: 'Plugin', value: txn.plugin_system ?? <span className="muted">None</span> },
          { label: 'Initiator', value: <Player name={txn.initiator_name} uuid={txn.initiator_uuid} /> },
        ]}
      />

      <Section title="Postings" level={3} description={`${postings.length} ${postings.length === 1 ? 'leg' : 'legs'}, summing to zero`}>
        <DataTable
          id={`postings-${txn.txn_id}`}
          label={`Postings of transaction #${txn.txn_id}`}
          columns={postingColumns}
          rows={postings}
          rowKey={(p) => p.posting_id}
          rowHref={(p) => `/accounts/${p.account_id}`}
          density="compact"
          link={RouterLink}
        />
      </Section>

      {showFollow && (
        <Section
          title="Follow the money"
          level={3}
          description={`What the receiving ${followed.length === 1 ? 'account' : 'accounts'} did ${hours} ${hours === 1 ? 'hour' : 'hours'} either side`}
          actions={<Segmented
            label="Follow window"
            value={String(hours)}
            items={FOLLOW_HOURS.map((h) => ({ value: String(h), label: `${h}h`, href: followHref(h) }))}
          />}
        >
          <div className={styles.follow}>
          {followed.length === 0 && <p className="muted">Nothing was received in this transaction.</p>}
          {followed.map((f) => {
            const rows: MovementRow[] = [
              ...[...f.before].reverse().map((m) => ({ ...m, current: false })),
              { txn_id: txn.txn_id, settlement_time: txn.settlement_time ?? new Date(0), message: txn.message, plugin_system: txn.plugin_system, amount: f.received, current: true },
              ...f.after.map((m) => ({ ...m, current: false })),
            ];
            return (
              <Section
                key={f.account_id}
                level={3}
                title={<Link href={`/accounts/${f.account_id}`} prefetch={false}>{f.label} <span className="dt-sub">#{f.account_id}</span></Link>}
                description={f.before.length + f.after.length === 0 ? 'No other transactions in this window.' : undefined}
              >
                <DataTable
                  id={`follow-${txn.txn_id}-${f.account_id}`}
                  label={`Transactions on ${f.label} around #${txn.txn_id}`}
                  columns={movementColumns()}
                  rows={rows}
                  rowKey={(m) => m.txn_id}
                  rowHref={(m) => txnHref(m.txn_id)}
                  density="compact"
                  link={RouterLink}
                />
              </Section>
            );
          })}
          </div>
        </Section>
      )}
    </div>
  );
}
