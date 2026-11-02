'use client';

import { useRef, useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { useViewer } from '@/components/ViewerContext';
import { adminTransferAction } from '@/lib/actions/admin-accounts';
import styles from '../admin.module.css';

/** Admin tool: move money between any two accounts with a memo. */
export function MoveMoneyPanel({ disabled }: { disabled: boolean }) {
  const router = useRouter();
  const { minecraftUuid } = useViewer();
  const [pending, start] = useTransition();
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [amount, setAmount] = useState('');
  const [memo, setMemo] = useState('');
  const attempt = useRef<{ body: string; key: string } | null>(null);
  const [msg, setMsg] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  function submit(e: React.FormEvent) {
    e.preventDefault();
    setMsg(null);
    const fromId = Number(from), toId = Number(to);
    if (!fromId || !toId) { setMsg({ kind: 'err', text: 'From and to account ids are required.' }); return; }
    const body = { fromAccountId: fromId, toAccountId: toId, amount: amount.trim(), memo: memo.trim() };
    const serialized = JSON.stringify(body);
    const storageKey = `admin-transfer:${minecraftUuid}`;
    try {
      const stored = sessionStorage.getItem(storageKey);
      if (stored) attempt.current = JSON.parse(stored);
    } catch { /* In-memory retry protection still works when storage is unavailable. */ }
    if (!attempt.current || attempt.current.body !== serialized) {
      attempt.current = { body: serialized, key: crypto.randomUUID() };
    }
    const idempotencyKey = attempt.current.key;
    try { sessionStorage.setItem(storageKey, JSON.stringify(attempt.current)); } catch { /* See above. */ }
    start(async () => {
      try {
      const r = await adminTransferAction({ ...body, idempotencyKey });
      if (!r.ok) setMsg({ kind: 'err', text: r.error ?? 'Transfer failed.' });
      else {
        setMsg({ kind: 'ok', text: `Transferred — txn #${r.result?.txnId}.` });
        attempt.current = null;
        try { sessionStorage.removeItem(storageKey); } catch { /* See above. */ }
        setAmount(''); setMemo('');
        router.refresh();
      }
      } catch {
        setMsg({ kind: 'err', text: 'The result is unknown. Retry with the same details to recover this transfer.' });
      }
    });
  }

  return (
    <form onSubmit={submit} className={styles.stack}>
      <p className={styles.hint}>If the result is unknown, retry with identical details in this tab. Changing the details starts a new transfer.</p>
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
