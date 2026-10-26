'use client';

import { useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { adminTransferAction } from '@/lib/actions/admin-accounts';
import styles from '../admin.module.css';

/** Admin tool: move money between any two accounts with a memo. */
export function MoveMoneyPanel({ disabled }: { disabled: boolean }) {
  const router = useRouter();
  const [pending, start] = useTransition();
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('');
  const [memo, setMemo] = useState('');
  const [msg, setMsg] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  function submit(e: React.FormEvent) {
    e.preventDefault();
    setMsg(null);
    const fromId = Number(from), toId = Number(to);
    if (!fromId || !toId) { setMsg({ kind: 'err', text: 'From and to account ids are required.' }); return; }
    start(async () => {
      const r = await adminTransferAction({ fromAccountId: fromId, toAccountId: toId, amount: amount.trim(), memo: memo.trim() });
      if (!r.ok) setMsg({ kind: 'err', text: r.error ?? 'Transfer failed.' });
      else {
        setMsg({ kind: 'ok', text: `Transferred — txn #${r.result?.txnId}.` });
        setAmount(''); setMemo('');
        router.refresh();
      }
    });
  }

  return (
    <form onSubmit={submit} className={styles.stack}>
      <div className={styles.row}>
        <label className="field">
          <span className="field-label">From account</span>
          <input className={`input ${styles.narrow}`} value={from} onChange={(e) => setFrom(e.target.value)} placeholder="From account #" inputMode="numeric" />
        </label>
        <label className="field">
          <span className="field-label">To account</span>
          <input className={`input ${styles.narrow}`} value={to} onChange={(e) => setTo(e.target.value)} placeholder="To account #" inputMode="numeric" />
        </label>
        <label className="field">
          <span className="field-label">Amount</span>
          <input className={`input ${styles.narrow}`} value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="Amount" inputMode="decimal" />
        </label>
        <label className={`field ${styles.grow}`}>
          <span className="field-label">Memo</span>
          <input className="input" value={memo} onChange={(e) => setMemo(e.target.value)} placeholder="Memo" />
        </label>
        <button className="btn btn-primary" disabled={disabled || pending || !from || !to || !amount.trim()}>{pending ? 'Working…' : 'Transfer'}</button>
      </div>
      {msg && <p role={msg.kind === 'err' ? 'alert' : 'status'} className={`${msg.kind === 'ok' ? 'state-ok' : 'state-error'} ${styles.message}`}>{msg.text}</p>}
    </form>
  );
}
