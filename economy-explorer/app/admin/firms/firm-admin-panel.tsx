'use client';

import { useViewer } from '@/components/ViewerContext';
import { useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { disbandFirmAction, renameFirmAction, updateFirmDetailsAction } from '@/lib/actions/admin-firms';
import { formatMoney, sum, tryParseMoney } from '@/lib/money';
import styles from '../admin.module.css';

/**
 * Per-firm admin controls: rename, edit details (HQ/Discord), and a type-to-confirm
 * disband. All call the audited server actions (which proxy the ledger-authoritative
 * treasury admin API).
 */
export function FirmAdminPanel({
  firmId, name, totalBalance, discordUrl, hqRegion, disabled,
}: { firmId: number; name: string; totalBalance: string; discordUrl: string | null; hqRegion: string | null; disabled: boolean }) {
  const router = useRouter();
  const { permissions } = useViewer();
  const [pending, start] = useTransition();
  const [newName, setNewName] = useState(name);
  const [discord, setDiscord] = useState(discordUrl ?? '');
  const [hq, setHq] = useState(hqRegion ?? '');
  const [confirm, setConfirm] = useState('');
  const [msg, setMsg] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  function doDetails(e: React.FormEvent) {
    e.preventDefault();
    setMsg(null);
    start(async () => {
      const r = await updateFirmDetailsAction(firmId, { discordUrl: discord.trim() || null, hqRegion: hq.trim() || null });
      if (!r.ok) setMsg({ kind: 'err', text: r.error ?? 'Update failed.' });
      else { setMsg({ kind: 'ok', text: 'Details updated.' }); router.refresh(); }
    });
  }

  function doRename(e: React.FormEvent) {
    e.preventDefault();
    setMsg(null);
    start(async () => {
      const r = await renameFirmAction(firmId, newName);
      if (!r.ok) setMsg({ kind: 'err', text: r.error ?? 'Rename failed.' });
      else { setMsg({ kind: 'ok', text: `Renamed to “${r.result?.displayName}”.` }); router.refresh(); }
    });
  }

  function doDisband(e: React.FormEvent) {
    e.preventDefault();
    setMsg(null);
    start(async () => {
      const r = await disbandFirmAction(firmId, confirm);
      if (!r.ok) setMsg({ kind: 'err', text: r.error ?? 'Disband failed.' });
      else {
        const swept = (r.result?.accounts ?? []).filter((a) => a.sweptAmount);
        const total = sum(swept.map((a) => tryParseMoney(a.sweptAmount) ?? 0n));
        setMsg({
          kind: 'ok',
          text: `Disbanded. ${r.result?.accounts.length ?? 0} account(s) archived; swept ${formatMoney(total, { compact: true })} to the proprietor.`,
        });
        setConfirm('');
        router.refresh();
      }
    });
  }

  const confirmMatches = confirm.trim() === name;

  return (
    <div className={styles.stack}>
      <form onSubmit={doRename} className={styles.row}>
        <label className={`field ${styles.grow}`}>
          <span className="field-label">Name</span>
          <input className="input" value={newName} onChange={(e) => setNewName(e.target.value)} aria-label="New firm name" />
        </label>
        <button className="btn" disabled={!permissions.includes('firm.rename') || disabled || pending || !newName.trim() || newName.trim() === name}>
          {pending ? 'Working…' : 'Rename'}
        </button>
      </form>

      <form onSubmit={doDetails} className={styles.row}>
        <label className="field">
          <span className="field-label">HQ region</span>
          <input className="input" value={hq} onChange={(e) => setHq(e.target.value)} aria-label="HQ region" />
        </label>
        <label className={`field ${styles.grow}`}>
          <span className="field-label">Discord URL</span>
          <input className="input" value={discord} onChange={(e) => setDiscord(e.target.value)} aria-label="Discord URL" />
        </label>
        <button className="btn" disabled={!permissions.includes('firm.details.update') || disabled || pending || (discord.trim() === (discordUrl ?? '') && hq.trim() === (hqRegion ?? ''))}>
          {pending ? 'Working…' : 'Save details'}
        </button>
      </form>

      <form onSubmit={doDisband} className={`${styles.stack} ${styles.dangerZone}`}>
        <p className={styles.hint}>
          Sweeps every account’s balance ({formatMoney(totalBalance, { compact: true })}) to the proprietor, archives the accounts,
          and archives the firm. This cannot be undone. Type <strong>{name}</strong> to confirm.
        </p>
        <div className={styles.row}>
          <input className={`input ${styles.grow}`} value={confirm} onChange={(e) => setConfirm(e.target.value)} placeholder={name} aria-label="Type firm name to confirm" />
          <button className={`btn ${styles.danger}`} disabled={!permissions.includes('firm.disband') || disabled || pending || !confirmMatches}>
            {pending ? 'Working…' : 'Disband'}
          </button>
        </div>
      </form>

      {msg && <p role={msg.kind === 'err' ? 'alert' : 'status'} className={`${msg.kind === 'ok' ? 'state-ok' : 'state-error'} ${styles.message}`}>{msg.text}</p>}
    </div>
  );
}
