'use client';
import { useState, useTransition } from 'react';
import { setGroupCapabilitiesAction } from '@/lib/actions/admin';

export function CapabilitiesForm({
  groupId,
  all,
  selected,
  disabled = false,
}: {
  groupId: number;
  all: { value: string; label: string; description: string }[];
  selected: string[];
  disabled?: boolean;
}) {
  const [pending, start] = useTransition();
  const [chosen, setChosen] = useState<Set<string>>(new Set(selected));
  // The last-known-saved set, so "dirty" is correct whether or not the server
  // action revalidates the page (the screen reflects the synced state on load).
  const [savedSet, setSavedSet] = useState<Set<string>>(new Set(selected));
  const [err, setErr] = useState<string | null>(null);
  const [justSaved, setJustSaved] = useState(false);

  const dirty = chosen.size !== savedSet.size || [...chosen].some((c) => !savedSet.has(c));

  function toggle(v: string) {
    setJustSaved(false);
    setChosen((prev) => {
      const next = new Set(prev);
      if (next.has(v)) next.delete(v); else next.add(v);
      return next;
    });
  }

  function onSave() {
    setErr(null);
    start(async () => {
      const r = await setGroupCapabilitiesAction(groupId, [...chosen]);
      if (!r.ok) {
        setErr(r.error ?? 'Failed');
      } else {
        setSavedSet(new Set(chosen));
        setJustSaved(true);
        window.location.reload();
      }
    });
  }

  return (
    <div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginBottom: 12 }}>
        {all.map((c) => (
          <label
            key={c.value}
            style={{
              display: 'grid',
              gridTemplateColumns: 'auto 1fr',
              gap: 10,
              alignItems: 'start',
              padding: '10px 12px',
              border: '1px solid var(--border, #2a2a2a)',
              borderRadius: 8,
              cursor: 'pointer',
              background: chosen.has(c.value) ? 'var(--surface-2, rgba(255,255,255,0.03))' : 'transparent',
            }}
          >
            <input
              type="checkbox"
              disabled={disabled || pending}
              checked={chosen.has(c.value)}
              onChange={() => toggle(c.value)}
              style={{ marginTop: 3 }}
            />
            <div>
              <div style={{ display: 'flex', gap: 8, alignItems: 'baseline', flexWrap: 'wrap' }}>
                <strong>{c.label}</strong>
                <span className="mono sub">{c.value}</span>
              </div>
              <div className="sub" style={{ marginTop: 2, lineHeight: 1.4 }}>{c.description}</div>
            </div>
          </label>
        ))}
      </div>
      <button className="btn" disabled={disabled || pending || !dirty} onClick={onSave}>
        {pending ? 'Saving…' : 'Save roles'}
      </button>
      {dirty
        ? <span className="sub" style={{ marginLeft: 8 }}>Unsaved changes.</span>
        : <span className="sub" style={{ marginLeft: 8 }}>{justSaved ? 'Saved.' : 'In sync.'}</span>}
      {err && <span className="state-error" style={{ fontSize: 11, marginLeft: 8 }}>{err}</span>}
    </div>
  );
}
