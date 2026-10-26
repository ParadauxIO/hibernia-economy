'use client';

import { useState, useTransition } from 'react';
import { DescriptionList, Tag, TableWrap, When } from '@democracycraft/design-system';
import { Dialog } from '@democracycraft/design-system/popover';
import { getAuditEntryAction } from '@/lib/actions/admin-audit';
import { formatAuditValue, type AuditDiffRow } from '@/lib/auditDiff';
import type { AuditEntryDetail } from '@/lib/services/audit';

const CHANGE_LABEL: Record<AuditDiffRow['change'], string> = { added: 'Added', removed: 'Removed', changed: 'Changed', same: 'Unchanged' };
const CHANGE_TONE: Record<AuditDiffRow['change'], 'pos' | 'neg' | 'warn' | 'neutral'> = { added: 'pos', removed: 'neg', changed: 'warn', same: 'neutral' };

/** "Details" for one audit row: the request facts and, for changes, a before/after diff. */
export function AuditDetailButton({ auditId, actionLabel }: { auditId: number; actionLabel: string }) {
  const [open, setOpen] = useState(false);
  const [detail, setDetail] = useState<AuditEntryDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, start] = useTransition();

  function show() {
    setOpen(true);
    if (detail) return;
    setError(null);
    start(async () => {
      const r = await getAuditEntryAction(auditId);
      if (r.ok && r.detail) setDetail(r.detail);
      else setError(r.error ?? 'Could not load this entry.');
    });
  }

  const changed = detail?.diff.filter((d) => d.change !== 'same') ?? [];
  const unchanged = detail?.diff.filter((d) => d.change === 'same') ?? [];

  return (
    <>
      <button type="button" className="btn btn-quiet" onClick={show} aria-label={`Details for entry ${auditId}`}>Details</button>
      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        size="lg"
        title={`${actionLabel} · entry ${auditId}`}
        actions={<button type="button" className="btn" onClick={() => setOpen(false)}>Close</button>}
      >
        {pending && <p role="status">Loading…</p>}
        {error && <p role="alert" className="state-error">{error}</p>}
        {detail && (
          <>
            <DescriptionList
              columns={2}
              items={[
                { label: 'When', value: <When value={detail.entry.at} time relative={false} /> },
                { label: 'Actor', value: detail.entry.actor_name ?? detail.entry.actor_uuid ?? detail.entry.actor_sub },
                { label: 'Role shown', value: detail.entry.actor_role ?? '—' },
                { label: 'Outcome', value: String(detail.entry.outcome) },
                { label: 'Request', value: <span className="mono">{detail.entry.method} {detail.entry.path}</span> },
                { label: 'Target', value: <span className="mono">{detail.entry.target_type ?? '—'} {detail.entry.target_id ?? ''}</span> },
                { label: 'Source IP', value: <span className="mono">{detail.entry.source_ip ?? '—'}</span> },
                { label: 'Sign-in subject', value: <span className="mono">{detail.entry.actor_sub}</span> },
              ]}
            />
            {detail.diff.length === 0 || (detail.before === null && detail.after === null) ? (
              <p className="muted">No before or after values were recorded for this entry.</p>
            ) : (
              <TableWrap label="What changed">
                <table className="data-table">
                  <caption className="sr-only">What changed</caption>
                  <thead><tr><th scope="col">Field</th><th scope="col">Before</th><th scope="col">After</th><th scope="col">Change</th></tr></thead>
                  <tbody>
                    {[...changed, ...unchanged].map((d) => (
                      <tr key={d.key || '(value)'}>
                        <td className="mono">{d.key || 'value'}</td>
                        <td className="mono">{formatAuditValue(d.before)}</td>
                        <td className="mono">{formatAuditValue(d.after)}</td>
                        <td><Tag tone={CHANGE_TONE[d.change]}>{CHANGE_LABEL[d.change]}</Tag></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </TableWrap>
            )}
          </>
        )}
      </Dialog>
    </>
  );
}
