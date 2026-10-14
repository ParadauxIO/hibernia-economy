'use client';

import { useViewer } from '@/components/ViewerContext';
import { useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { disbandFirmAction, renameFirmAction, updateFirmDetailsAction } from '@/lib/actions/admin-firms';
import { fmtAmt } from '@/lib/format';

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
        const total = swept.reduce((s, a) => s + Number(a.sweptAmount), 0);
        setMsg({
          kind: 'ok',
          text: `Disbanded. ${r.result?.accounts.length ?? 0} account(s) archived; swept ${fmtAmt(total)} to the proprietor.`,
        });
        setConfirm('');
        router.refresh();
      }
    });
  }

  const confirmMatches = confirm.trim() === name;

  return (
    <div style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 10 }}>
      <form onSubmit={doRename} style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <input className="input" value={newName} onChange={(e) => setNewName(e.target.value)} aria-label="New firm name" style={{ minWidth: 220 }} />
        <button className="btn" disabled={!permissions.includes('firm.rename') || disabled || pending || !newName.trim() || newName.trim() === name}>
          {pending ? 'Working…' : 'Rename'}
        </button>
      </form>

      <form onSubmit={doDetails} style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <input className="input" value={hq} onChange={(e) => setHq(e.target.value)} aria-label="HQ region" placeholder="HQ region" style={{ minWidth: 160 }} />
        <input className="input" value={discord} onChange={(e) => setDiscord(e.target.value)} aria-label="Discord URL" placeholder="Discord URL" style={{ minWidth: 220 }} />
        <button className="btn" disabled={!permissions.includes('firm.details.update') || disabled || pending || (discord.trim() === (discordUrl ?? '') && hq.trim() === (hqRegion ?? ''))}>
          {pending ? 'Working…' : 'Save details'}
        </button>
      </form>

      <details>
        <summary style={{ cursor: 'pointer', color: 'var(--bad)', fontSize: 13 }}>Disband this firm…</summary>
        <form onSubmit={doDisband} style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 6, maxWidth: 460 }}>
          <p className="muted small" style={{ margin: 0 }}>
            Sweeps every account’s balance ({fmtAmt(totalBalance)}) to the proprietor, archives the accounts,
            and archives the firm. This cannot be undone. Type <strong>{name}</strong> to confirm.
          </p>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <input className="input" value={confirm} onChange={(e) => setConfirm(e.target.value)} placeholder={name} aria-label="Type firm name to confirm" style={{ minWidth: 220 }} />
            <button className="btn" style={{ background: 'var(--bad)', color: '#fff' }} disabled={!permissions.includes('firm.disband') || disabled || pending || !confirmMatches}>
              {pending ? 'Working…' : 'Disband'}
            </button>
          </div>
        </form>
      </details>

      {msg && <span className={msg.kind === 'ok' ? 'state-ok' : 'state-error'} style={{ fontSize: 12 }}>{msg.text}</span>}
    </div>
  );
}
