'use client';
import { useState, useTransition } from 'react';
import { setDirectRoleMemberAction } from '@/lib/actions/roles';
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
  return <>
    <p className="sub">Direct assignments are independent of group membership. Removing a direct assignment does not remove access granted by a group.</p>
    {canAssign && <form onSubmit={event => { event.preventDefault(); change(identifier, true); }} style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 16 }}>
      <label>Player name or UUID <input className="input" value={identifier} onChange={event => setIdentifier(event.target.value)} required /></label>
      <button className="btn" disabled={pending || !identifier.trim()}>Assign role</button>
    </form>}
    <div className="table-wrap"><table className="data-table"><thead><tr><th>Player</th><th>Assignment</th></tr></thead><tbody>
      {!members.length && <tr><td colSpan={2}>No direct assignments.</td></tr>}
      {members.map(member => <tr key={member.uuid}><td>{member.name ?? member.uuid}</td><td>
        {canAssign ? <button className="btn" disabled={pending} onClick={() => change(member.uuid, false)}>Remove direct assignment</button> : 'Direct'}
      </td></tr>)}
    </tbody></table></div>
    {error && <p role="alert" className="state-error">{error}</p>}
  </>;
}
