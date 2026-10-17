'use client';

import { useViewer } from '@/components/ViewerContext';
import { useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import {
  renameAccountAction, changeAccountOwnerAction, archiveAccountAction, unarchiveAccountAction,
} from '@/lib/actions/admin-accounts';

/** Per-account admin controls: rename, change owner, archive/unarchive. */
export function AccountAdminPanel({
  accountId, displayName, archived,
}: { accountId: number; displayName: string | null; archived: boolean }) {
  const router = useRouter();
  const { permissions } = useViewer();
  const [pending, start] = useTransition();
  const [name, setName] = useState(displayName ?? '');
  const [owner, setOwner] = useState('');
  const [msg, setMsg] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);

  function act(p: Promise<{ ok: boolean; error?: string }>, okText: string) {
    setMsg(null);
    start(async () => {
      const r = await p;
      if (!r.ok) setMsg({ kind: 'err', text: r.error ?? 'Failed.' });
      else { setMsg({ kind: 'ok', text: okText }); router.refresh(); }
    });
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 8 }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <input className="input" value={name} onChange={(e) => setName(e.target.value)} aria-label="Display name" placeholder="Display name" style={{ minWidth: 200 }} />
        <button className="btn" disabled={!permissions.includes('account.rename') || pending || !name.trim() || name === (displayName ?? '')}
          onClick={() => act(renameAccountAction(accountId, name), 'Renamed.')}>Rename</button>
      </div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <input className="input" value={owner} onChange={(e) => setOwner(e.target.value)} aria-label="New owner" placeholder="New owner (player name or UUID)" style={{ minWidth: 240 }} />
        <button className="btn" disabled={!permissions.includes('account.owner.change') || pending || !owner.trim()}
          onClick={() => act(changeAccountOwnerAction(accountId, owner), 'Owner changed.')}>Change owner</button>
        {archived ? (
          <button className="btn" disabled={!permissions.includes('account.archive') || pending} onClick={() => act(unarchiveAccountAction(accountId), 'Unarchived.')}>Unarchive</button>
        ) : (
          <button className="btn" style={{ background: 'var(--bad)', color: '#fff' }} disabled={!permissions.includes('account.archive') || pending}
            onClick={() => act(archiveAccountAction(accountId), 'Archived.')}>Archive</button>
        )}
      </div>
      {msg && <span className={msg.kind === 'ok' ? 'state-ok' : 'state-error'} style={{ fontSize: 12 }}>{msg.text}</span>}
    </div>
  );
}
