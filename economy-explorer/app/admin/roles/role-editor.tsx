'use client';
import { useState, useTransition } from 'react';
import { PERMISSIONS, PERMISSION_DESCRIPTIONS, type Permission } from '@/lib/auth/policy';
import type { RoleDefinition } from '@/lib/sql/role';
import { createRoleAction, updateRoleAction, deleteRoleAction } from '@/lib/actions/roles';

export function RoleEditor({ role, actorPermissions }: { role?: RoleDefinition; actorPermissions: readonly Permission[] }) {
  const [key, setKey] = useState(role?.key ?? '');
  const [name, setName] = useState(role?.name ?? '');
  const [description, setDescription] = useState(role?.description ?? '');
  const [selected, setSelected] = useState(new Set<Permission>(role?.permissions ?? []));
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();
  const delegable = !role || role.permissions.every(p => actorPermissions.includes(p));
  const canSave = delegable && actorPermissions.includes(role ? 'roles.update' : 'roles.create');
  const canDelete = role && role.key !== 'player' && delegable && actorPermissions.includes('roles.delete');
  const groups = [...new Set(PERMISSIONS.map(p => p.split('.')[0]))];

  function save(event: React.FormEvent) {
    event.preventDefault(); setError(null);
    startTransition(async () => {
      const value = { key, name, description: description || null, permissions: [...selected] };
      const result = role ? await updateRoleAction(value, role.revision) : await createRoleAction(value);
      if (!result.ok) setError(result.error ?? 'Could not save the role.');
      else window.location.assign(`/admin/roles/${encodeURIComponent(key)}`);
    });
  }
  function remove() {
    if (!role || !confirm(`Delete ${role.name}? All direct and group assignments will be removed.`)) return;
    setError(null);
    startTransition(async () => {
      const result = await deleteRoleAction(role.key, role.revision);
      if (!result.ok) setError(result.error ?? 'Could not delete the role.');
      else window.location.assign('/admin/roles');
    });
  }
  return <form onSubmit={save}>
    <fieldset disabled={pending || !canSave} style={{ border: 0, padding: 0 }}>
      <label style={{ display: 'block', marginBottom: 12 }}>Role key<br />
        <input className="input" value={key} onChange={e => setKey(e.target.value)} readOnly={!!role} required pattern="[a-z][a-z0-9_-]{0,31}" maxLength={32} />
      </label>
      <label style={{ display: 'block', marginBottom: 12 }}>Display name<br />
        <input className="input" value={name} onChange={e => setName(e.target.value)} required maxLength={64} />
      </label>
      <label style={{ display: 'block', marginBottom: 12 }}>Description<br />
        <input className="input" value={description} onChange={e => setDescription(e.target.value)} maxLength={255} style={{ width: '100%' }} />
      </label>
      <p className="sub">Roles combine. Scoped read permissions still require an account or firm relationship. You can only grant permissions you hold.</p>
      {groups.map(group => <fieldset key={group} style={{ marginBottom: 16, border: '1px solid var(--border)', padding: 12 }}>
        <legend>{group.replaceAll('_', ' ')}</legend>
        {PERMISSIONS.filter(p => p.startsWith(`${group}.`)).map(permission => <label key={permission} style={{ display: 'flex', gap: 10, marginBottom: 10, alignItems: 'start' }}>
          <input type="checkbox" checked={selected.has(permission)} disabled={!actorPermissions.includes(permission)} onChange={e => {
            const checked = e.target.checked;
            setSelected(previous => {
              const next = new Set(previous); if (checked) next.add(permission); else next.delete(permission); return next;
            });
          }} />
          <span><strong>{permission}</strong><br /><span className="sub">{PERMISSION_DESCRIPTIONS[permission]}</span></span>
        </label>)}
      </fieldset>)}
      <button type="submit" className="btn btn-primary">{pending ? 'Saving…' : role ? 'Save permissions' : 'Create role'}</button>
    </fieldset>
    {canDelete && <button type="button" className="btn" disabled={pending} onClick={remove} style={{ marginTop: 16 }}>Delete role</button>}
    {role?.key === 'player' && <p className="sub">Player is the signed-in baseline. Its permissions are editable; the role itself cannot be deleted.</p>}
    {error && <p role="alert" className="state-error">{error}</p>}
  </form>;
}
