import 'server-only';
import { hasPermission } from '@/lib/auth/policy';
import type { KeysetCursor } from '@/lib/pagination/cursor';
import { listAuditPage, type AuditEntry } from '@/lib/sql/audit';
import { auditTable } from '@/lib/tables/audit';
import { defineExport } from '../registry';

// The audit log (/admin/audit), gated by audit.read exactly like the page.
// Keyset batches on (at, audit_id). Before/after values stay in the detail
// dialog; the export carries the row facts.

defineExport<typeof auditTable, AuditEntry, KeysetCursor>({
  id: 'audit',
  title: 'Audit log',
  table: auditTable,
  authorize: (viewer) => hasPermission(viewer, 'audit.read'),
  rows: async (state, cursor, { limit }) => {
    const page = await listAuditPage({ ...state, limit }, cursor);
    const rows = page.slice(0, limit);
    const last = rows.at(-1);
    return { rows, next: page.length > limit && last ? { at: last.at, id: last.audit_id } : null };
  },
  columns: [
    { key: 'audit_id', header: 'Entry', value: (e) => e.audit_id },
    { key: 'at', header: 'Time (UTC)', value: (e) => e.at.toISOString() },
    { key: 'actor_name', header: 'Actor', value: (e) => e.actor_name },
    { key: 'actor_uuid', header: 'Actor UUID', value: (e) => e.actor_uuid },
    { key: 'actor_role', header: 'Role', value: (e) => e.actor_role },
    { key: 'action', header: 'Action', value: (e) => e.action },
    { key: 'method', header: 'Method', value: (e) => e.method },
    { key: 'path', header: 'Path', value: (e) => e.path },
    { key: 'target_type', header: 'Target type', value: (e) => e.target_type },
    { key: 'target_id', header: 'Target id', value: (e) => e.target_id },
    { key: 'outcome', header: 'Outcome', value: (e) => e.outcome },
    { key: 'has_detail', header: 'Has before/after', value: (e) => (e.has_detail ? 'yes' : 'no') },
  ],
});
