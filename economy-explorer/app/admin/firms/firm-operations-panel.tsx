'use client';
import { useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { useViewer } from '@/components/ViewerContext';
import { operateFirmAction } from '@/lib/actions/admin-firms';
import styles from '../admin.module.css';
export function FirmOperationsPanel({ firmId, name, archived, nameReleased, taxExempt, disabled }: {
  firmId: number; name: string; archived: boolean; nameReleased: boolean; taxExempt: boolean; disabled: boolean;
}) {
  const { permissions } = useViewer();
  const router = useRouter();
  const [owner, setOwner] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [pending, start] = useTransition();
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  function run(input: Parameters<typeof operateFirmAction>[1]) {
    setMessage(null);
    start(async () => {
      const r = await operateFirmAction(firmId, input);
      setMessage({ ok: r.ok, text: r.ok ? `Saved.${r.result?.accountIds.length ? ` Reconciled ${r.result.accountIds.length} account(s).` : ''}` : r.error ?? 'Failed.' });
      if (r.ok) { setConfirmation(''); router.refresh(); }
    });
  }
  const blocked = pending || disabled;
  return <div className={styles.stack}>
    <p>Balance-tax exemption: {taxExempt ? 'Exempt' : 'Not exempt'}. Reserved name: {nameReleased ? 'Released' : 'Reserved'}.</p>
    {!archived && <>
      <button className="btn" disabled={blocked || !permissions.includes('firm.tax.update')} onClick={() => run({ operation: 'tax-exemption', exempt: !taxExempt })}>{taxExempt ? 'Remove tax exemption' : 'Exempt from balance tax'}</button>
      <p>Reconcile account owners and access from current staff roles: proprietor and ADMIN authorize; FINANCIAL members spend. Stale member/authorizer grants are removed. Independent viewer and group grants remain.</p>
      <button className="btn" disabled={blocked || !permissions.includes('firm.accounts.sync')} onClick={() => {
        if (confirm('Replace account ownership and member/authorizer access with the firm’s current proprietor and staff roles?')) run({ operation: 'sync-accounts' });
      }}>Reconcile account access</button>
      <label className="field"><span className="field-label">New proprietor (name or UUID)</span><input className="input" value={owner} onChange={e => setOwner(e.target.value)} /></label>
      <p>Reassigning transfers linked accounts, ends both proprietors’ employee memberships, cancels pending transfers and revokes the outgoing proprietor’s business API keys for this firm.</p>
    </>}
    {(!archived || !nameReleased) && <>
      <label className="field"><span className="field-label">Retype {name} to confirm</span><input className="input" value={confirmation} onChange={e => setConfirmation(e.target.value)} /></label>
      {archived ? <>
        <p>Releasing the name allows another firm to use it. History remains attached to this firm’s numeric ID.</p>
        <button className="btn" disabled={blocked || confirmation.trim() !== name || !permissions.includes('firm.name.release')} onClick={() => run({ operation: 'release-name', confirmName: confirmation })}>Release reserved name</button>
      </> : <button className="btn" disabled={blocked || !owner.trim() || confirmation.trim() !== name || !permissions.includes('firm.owner.change')} onClick={() => run({ operation: 'proprietor', owner, confirmName: confirmation })}>Change proprietor</button>}
    </>}
    {message && <p role={message.ok ? 'status' : 'alert'}>{message.text}</p>}
  </div>;
}
