'use client';
import { useState, useTransition } from 'react';
import { TableWrap } from '@democracycraft/design-system';
import { setDirectRoleMemberAction } from '@/lib/actions/roles';
import styles from '../../admin.module.css';

export function RoleMembers({ roleKey, members, canAssign }: { roleKey: string; members: { uuid: string; name: string | null }[]; canAssign: boolean }) {
  const [identifier, setIdentifier] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  function change(value: string, grant: boolean) {
    setError(null);
    startTransition(async () => {
      const result = await setDirectRoleMemberAction(roleKey, value, grant);
      if (!result.ok) setError(result.error ?? 'Assignment failed.');
      else window.location.reload();
    });
  }
  return <div className={styles.stack}>
    <p className={styles.hint}>Direct assignments are independent of group membership. Removing a direct assignment does not remove access granted by a group.</p>
    {canAssign && <form onSubmit={event => { event.preventDefault(); change(identifier, true); }} className={styles.row}>
      <label className={`field ${styles.grow}`}>
        <span className="field-label">Player name or UUID</span>
        <input className="input" aria-label="Player name or UUID" value={identifier} onChange={event => setIdentifier(event.target.value)} required />
      </label>
      <button className="btn" disabled={pending || !identifier.trim()}>Assign role</button>
    </form>}
    <TableWrap label="Role members"><table className="data-table">
      <caption className="sr-only">Role members</caption>
      <thead><tr><th scope="col">Player</th><th scope="col">Assignment</th></tr></thead>
      <tbody>
        {!members.length && <tr><td colSpan={2} className="empty">No direct assignments.</td></tr>}
        {members.map(member => <tr key={member.uuid}><td>{member.name ?? <span className="mono">{member.uuid}</span>}</td><td>
          {canAssign ? <button type="button" className="btn btn-quiet" disabled={pending} onClick={() => change(member.uuid, false)}>Remove direct assignment</button> : 'Direct'}
        </td></tr>)}
      </tbody>
    </table></TableWrap>
    {error && <p role="alert" className="state-error">{error}</p>}
  </div>;
}
