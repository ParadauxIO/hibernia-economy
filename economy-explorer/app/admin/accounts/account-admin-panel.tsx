'use client';

import { useViewer } from '@/components/ViewerContext';
import { useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import {
  renameAccountAction, changeAccountOwnerAction, archiveAccountAction, unarchiveAccountAction,
} from '@/lib/actions/admin-accounts';
import styles from '../admin.module.css';

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
    <div className={styles.stack}>
      <div className={styles.row}>
        <label className={`field ${styles.grow}`}>
          <span className="field-label">Display name</span>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} aria-label="Display name" />
        </label>
        <button type="button" className="btn" disabled={!permissions.includes('account.rename') || pending || !name.trim() || name === (displayName ?? '')}
          onClick={() => act(renameAccountAction(accountId, name), 'Renamed.')}>Rename</button>
      </div>
      <div className={styles.row}>
        <label className={`field ${styles.grow}`}>
          <span className="field-label">New owner (player name or UUID)</span>
          <input className="input" value={owner} onChange={(e) => setOwner(e.target.value)} aria-label="New owner" />
        </label>
        <button type="button" className="btn" disabled={!permissions.includes('account.owner.change') || pending || !owner.trim()}
          onClick={() => act(changeAccountOwnerAction(accountId, owner), 'Owner changed.')}>Change owner</button>
      </div>
      <div className={`${styles.row} ${styles.dangerZone}`}>
        <p className={styles.hint}>
          {archived
            ? 'This account is archived. Unarchiving lets it send and receive money again.'
            : 'Archiving closes the account: it keeps its history but can no longer send or receive money.'}
        </p>
        {archived ? (
          <button type="button" className="btn" disabled={!permissions.includes('account.archive') || pending} onClick={() => act(unarchiveAccountAction(accountId), 'Unarchived.')}>Unarchive</button>
        ) : (
          <button type="button" className={`btn ${styles.danger}`} disabled={!permissions.includes('account.archive') || pending}
            onClick={() => act(archiveAccountAction(accountId), 'Archived.')}>Archive</button>
        )}
      </div>
      {msg && <p role={msg.kind === 'err' ? 'alert' : 'status'} className={`${msg.kind === 'ok' ? 'state-ok' : 'state-error'} ${styles.message}`}>{msg.text}</p>}
    </div>
  );
}
