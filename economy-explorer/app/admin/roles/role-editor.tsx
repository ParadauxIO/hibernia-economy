'use client';
import { useState, useTransition } from 'react';
import { PERMISSIONS, PERMISSION_DESCRIPTIONS, type Permission } from '@/lib/auth/policy';
import type { RoleDefinition } from '@/lib/sql/role';
import { createRoleAction, updateRoleAction, deleteRoleAction } from '@/lib/actions/roles';
import styles from '../admin.module.css';

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
  const groups = [...new Set(PERMISSIONS.map(p => p.split('.')[0] ?? p))];

  function save(event: React.FormEvent) {
    event.preventDefault(); setError(null);
    startTransition(async () => {
      const value = { key, name, description: description || null, permissions: [...selected] };
      const result = role ? await updateRoleAction(value, role.revision) : await createRoleAction(value);
      if (!result.ok) setError(result.error ?? 'Could not save the role.');
      // A full document load on purpose: every server component (nav, gates)
      // must re-resolve the changed grants, not just this route segment.
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination
      else window.location.assign(`/admin/roles/${encodeURIComponent(key)}`);
    });
  }
  function remove() {
    if (!role || !confirm(`Delete ${role.name}? All direct and group assignments will be removed.`)) return;
    setError(null);
    startTransition(async () => {
      const result = await deleteRoleAction(role.key, role.revision);
      if (!result.ok) setError(result.error ?? 'Could not delete the role.');
      // Full document load, as after a save.
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination
      else window.location.assign('/admin/roles');
    });
  }
  return <form onSubmit={save} className={styles.stack}>
    <fieldset disabled={pending || !canSave} className={`${styles.stack} ${styles.bare}`}>
      <div className={styles.row}>
        <label className="field">
          <span className="field-label">Role key</span>
          <input className="input mono" aria-label="Role key" value={key} onChange={e => setKey(e.target.value)} readOnly={!!role} required pattern="[a-z][a-z0-9_-]{0,31}" maxLength={32} />
        </label>
        <label className="field">
          <span className="field-label">Display name</span>
          <input className="input" aria-label="Display name" value={name} onChange={e => setName(e.target.value)} required maxLength={64} />
        </label>
        <label className={`field ${styles.grow}`}>
          <span className="field-label">Description</span>
          <input className="input" aria-label="Description" value={description} onChange={e => setDescription(e.target.value)} maxLength={255} />
        </label>
      </div>
      <p className={styles.hint}>Roles combine. Scoped read permissions still require an account or firm relationship. You can only grant permissions you hold.</p>
      {groups.map(group => <fieldset key={group} className={styles.permGroup}>
        <legend>{group.replaceAll('_', ' ')}</legend>
        <ul className={styles.checklist}>
          {PERMISSIONS.filter(p => p.startsWith(`${group}.`)).map(permission => <li key={permission}>
            <label className={styles.check}>
              <input type="checkbox" checked={selected.has(permission)} disabled={!actorPermissions.includes(permission)} onChange={e => {
                const checked = e.target.checked;
                setSelected(previous => {
                  const next = new Set(previous); if (checked) next.add(permission); else next.delete(permission); return next;
                });
              }} />
              <span><span className="mono">{permission}</span><br /><span className={styles.hint}>{PERMISSION_DESCRIPTIONS[permission]}</span></span>
            </label>
          </li>)}
        </ul>
      </fieldset>)}
      <div className={styles.row}>
        <button type="submit" className="btn btn-primary">{pending ? 'Saving…' : role ? 'Save permissions' : 'Create role'}</button>
      </div>
    </fieldset>
    {canDelete && <div className={styles.dangerZone}><button type="button" className={`btn ${styles.danger}`} disabled={pending} onClick={remove}>Delete role</button></div>}
    {role?.key === 'player' && <p className={styles.hint}>Player is the signed-in baseline. Its permissions are editable; the role itself cannot be deleted.</p>}
    {error && <p role="alert" className="state-error">{error}</p>}
  </form>;
}
