import type { AppHref } from '@/lib/routes';
import { AUDIT_ACTIONS, AUDIT_TARGET_TYPES } from '@/lib/tables/audit';

export const ACTION_LABEL = new Map<string, string>(AUDIT_ACTIONS.map((a) => [a.action, a.label]));
export const TARGET_LABEL = new Map<string, string>(AUDIT_TARGET_TYPES.map((t) => [t.value, t.label]));

/**
 * The target as a kind and value. Older rows stored groups, roles, keys and
 * webhooks as `global` + `group:12`; read those as the kind they name.
 */
export function targetOf(type: string | null, id: string | null): { kind: string | null; value: string | null } {
  if (type === 'global' && id && /^[a-z_]+:./.test(id)) {
    const [prefix = '', ...rest] = id.split(':');
    return { kind: prefix === 'key' ? 'api_key' : prefix, value: rest.join(':') };
  }
  return { kind: type, value: id };
}

/** Where a target can be opened, if anywhere. */
export function targetHref(kind: string | null, value: string | null): AppHref | null {
  if (!kind || !value) return null;
  switch (kind) {
    case 'account': return /^\d+$/.test(value) ? `/accounts/${value}` : null;
    case 'firm': return /^\d+$/.test(value) ? `/admin/firms/${value}` : null;
    case 'player': return /^[0-9a-f-]{36}$/i.test(value) ? `/admin/players/${value.toLowerCase()}` : null;
    case 'transaction': return /^\d+$/.test(value) ? `/transactions/${value}` : null;
    case 'group': return /^\d+$/.test(value) ? `/admin/groups/${value}` : null;
    case 'role': return `/admin/roles/${encodeURIComponent(value)}`;
    default: return null;
  }
}

/** The target as one line of text, e.g. "Account 42". */
export function targetText(kind: string | null, value: string | null): string {
  return `${TARGET_LABEL.get(kind ?? '') ?? kind ?? '—'}${value ? ` ${value}` : ''}`;
}
