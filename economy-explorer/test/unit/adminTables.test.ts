import { describe, expect, it, vi } from 'vitest';
import { ADMIN_TOOLS, adminToolsFor, canAccessAdmin } from '@/lib/auth/admin';
import { PERMISSIONS, type Permission } from '@/lib/auth/policy';
import type { Viewer } from '@/lib/auth/viewer';
import { auditColumnMap } from '@/lib/sql/audit';
import { compileTableState } from '@/lib/sql/tableState';
import { AUDIT_ACTIONS, actionValue, auditTable } from '@/lib/tables/audit';
import { adminRoleGrantsTable } from '@/lib/tables/admin';
import { parseTableState, serializeTableState } from '@/lib/tables/core';
import { grantFiltersFor } from '@/lib/services/role-grants';
import { permissionsFor } from '../role-fixtures';

vi.mock('@/lib/auth/viewer', () => ({ getViewer: vi.fn() }));
vi.mock('@/lib/audit', () => ({ auditView: vi.fn() }));
vi.mock('@/lib/db', () => ({ db: {}, uuidToBin: (u: string) => Buffer.from(u.replace(/-/g, ''), 'hex'), binToUuid: () => '' }));

const viewer = (permissions: Permission[]): Viewer => ({
  anon: false, keycloakSub: 's', minecraftUuid: null, minecraftName: null, linked: false, role: 'x', capabilities: [], permissions,
});

describe('admin tools', () => {
  it('lists role grants only for viewers holding both permissions its page checks', () => {
    const titles = (p: Permission[]) => adminToolsFor(viewer(p)).map((t) => t.title);
    expect(titles(['roles.read'])).toEqual(['Roles']);
    expect(titles(['roles.read', 'groups.read'])).toEqual(['Groups', 'Roles', 'Role grants']);
    expect(titles(['audit.read'])).toEqual(['Audit log']);
  });

  it('gives the seeded admin every tool and anonymous visitors none', () => {
    expect(adminToolsFor(viewer(permissionsFor('player', 'admin')))).toHaveLength(ADMIN_TOOLS.length);
    expect(canAccessAdmin({ anon: true, role: null, capabilities: [], permissions: [], keycloakSub: null, minecraftUuid: null, minecraftName: null, linked: false })).toBe(false);
  });

  it('uses only real permission keys', () => {
    for (const tool of ADMIN_TOOLS) for (const p of [tool.permission, ...(tool.alsoRequires ?? [])]) expect(PERMISSIONS).toContain(p);
  });
});

describe('audit table', () => {
  it('round-trips every action through its dot-free URL value', () => {
    for (const { action } of AUDIT_ACTIONS) {
      const state = parseTableState(new URLSearchParams(`f=action:in:${actionValue(action)}`), auditTable);
      expect(state.filters).toEqual([{ field: 'action', op: 'in', values: [actionValue(action)] }]);
    }
  });

  it('drops unknown actions, target types and malformed actors instead of erroring', () => {
    const state = parseTableState(new URLSearchParams('f=action:in:drop-tables&f=target:in:everything&f=actor:in:a;b'), auditTable);
    expect(state.filters).toEqual([]);
  });

  it('is cursor-paged, newest first, and keeps the cursor in the URL', () => {
    const state = parseTableState(new URLSearchParams('page=4'), auditTable);
    expect(state).toMatchObject({ page: 1, cursor: null, sort: { field: 'at', dir: 'desc' } });
    expect(serializeTableState(state, auditTable)).toBe('');
  });

  it('maps action chips back to stored actions as bound parameters', () => {
    const state = parseTableState(new URLSearchParams('f=action:in:account-owner-change,relationship-read'), auditTable);
    const compiled = compileTableState(state, auditColumnMap);
    expect(compiled.conditions).toHaveLength(1);
    const node = compiled.conditions[0]!.toOperationNode();
    expect(JSON.stringify(node)).toContain('account.owner.change');
    expect(JSON.stringify(node)).toContain('relationship.read');
  });
});

describe('role grant chips', () => {
  const filters = (qs: string) => grantFiltersFor(parseTableState(new URLSearchParams(qs), adminRoleGrantsTable));

  it('maps chips to the DAL filters, treating both statuses as no status filter', () => {
    expect(filters('q=bob&f=role:eq:compliance&f=source:in:luckperms,business&f=status:in:active')).toMatchObject({
      q: 'bob', role: 'compliance', sources: ['luckperms', 'business'], status: 'active', page: 1, sort: 'name', dir: 'asc',
    });
    expect(filters('f=status:in:active,inactive').status).toBeUndefined();
  });

  it('keeps old ?source=&status= links working', () => {
    expect(filters('source=luckperms&status=inactive')).toMatchObject({ sources: ['luckperms'], status: 'inactive' });
  });
});
