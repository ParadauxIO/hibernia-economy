'use client';
import { useState, useTransition } from 'react';
import { Tag, TableWrap } from '@democracycraft/design-system';
import { addGroupMemberAction, removeGroupMemberAction } from '@/lib/actions/admin';
import styles from '../../admin.module.css';

interface Member {
  uuid: string;
  name: string | null;
  source: 'manual' | 'luckperms' | 'business' | 'realty';
  syncActive: boolean;
}

export function MemberManager({ groupId, members, disabled = false }: { groupId: number; members: Member[]; disabled?: boolean }) {
  const [pending, start] = useTransition();
  const [identifier, setIdentifier] = useState('');
  const [err, setErr] = useState<string | null>(null);

  // Reload the authoritative snapshot after writes; streamed revalidation alone
  // can leave group controls pending during concurrent policy edits.
  function onAdd(e: React.FormEvent) {
    e.preventDefault();
    setErr(null);
    start(async () => {
      const r = await addGroupMemberAction(groupId, identifier);
      if (!r.ok) setErr(r.error ?? 'Failed'); else window.location.reload();
    });
  }

  function onRemove(uuid: string, name: string | null) {
    if (!confirm(`Remove ${name ?? uuid} from this group?`)) return;
    setErr(null);
    start(async () => {
      const r = await removeGroupMemberAction(groupId, uuid);
      if (!r.ok) setErr(r.error ?? 'Failed'); else window.location.reload();
    });
  }

  return (
    <div className={styles.stack}>
      <form onSubmit={onAdd} className={styles.row}>
        <label className={`field ${styles.grow}`}>
          <span className="field-label">Add a manual member</span>
          <input className="input" placeholder="Player name or UUID" value={identifier} onChange={(e) => setIdentifier(e.target.value)} />
        </label>
        <button className="btn" disabled={disabled || pending || !identifier.trim()}>{pending ? 'Adding…' : 'Add member'}</button>
        {err && <span role="alert" className="state-error small">{err}</span>}
      </form>

      <TableWrap label="Group members">
        <table className="data-table">
          <caption className="sr-only">Group members</caption>
          <thead>
            <tr><th scope="col">Player</th><th scope="col">Source</th><th scope="col"><span className="sr-only">Actions</span></th></tr>
          </thead>
          <tbody>
            {members.length === 0 && <tr><td colSpan={3} className="empty">No members.</td></tr>}
            {members.map((m) => (
              <tr key={`${m.uuid}:${m.source}`}>
                <td>{m.name ?? <span className="mono">{m.uuid}</span>}</td>
                <td>
                  {m.source !== 'manual'
                    ? <Tag tone={m.syncActive ? 'neutral' : 'warn'}>{m.source}: {m.syncActive ? 'synced' : 'sync expired'}</Tag>
                    : <Tag tone="accent">manual</Tag>}
                </td>
                <td>
                  {m.source === 'manual'
                    ? <button type="button" className="btn btn-quiet" disabled={disabled || pending} onClick={() => onRemove(m.uuid, m.name)}>Remove</button>
                    : <span className="muted small">Managed by sync</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableWrap>
    </div>
  );
}
