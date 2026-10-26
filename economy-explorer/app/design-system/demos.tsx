'use client';
// Interactive gallery demos. Fixtures only; nothing here talks to the server.

import { useState } from 'react';
import { Dialog, Popover, Segmented } from '@democracycraft/design-system';

const TYPES = ['Personal', 'Business', 'Government', 'System'];

/** The filter-chip editor pattern TABLE builds on: a chip that opens a small form. */
export function PopoverDemo() {
  const [chosen, setChosen] = useState<string[]>(['Business']);
  return <Popover label="Filter by account type" sheet trigger={<button type="button" className="filter-chip"><span>Type: <strong>{chosen.length ? chosen.join(', ') : 'Any'}</strong></span></button>}>
    {({ close }) => <form className="ds-popover-form" onSubmit={(e) => { e.preventDefault(); close(); }}>
      <fieldset>
        <legend className="field-label">Account type</legend>
        {TYPES.map((t) => <label key={t} className="ds-check">
          <input type="checkbox" checked={chosen.includes(t)} onChange={(e) => setChosen(e.target.checked ? [...chosen, t] : chosen.filter((c) => c !== t))} />
          {t}
        </label>)}
      </fieldset>
      <div className="ds-row"><button type="button" className="btn btn-quiet" onClick={() => setChosen([])}>Clear</button><button type="submit" className="btn btn-primary">Apply</button></div>
    </form>}
  </Popover>;
}

export function DialogDemo() {
  const [open, setOpen] = useState(false);
  return <>
    <button type="button" className="btn" onClick={() => setOpen(true)}>Open dialog</button>
    <Dialog open={open} onClose={() => setOpen(false)} title="Revoke API key?" description="Integrations using this key stop working immediately. This can't be undone."
      actions={<><button type="button" className="btn btn-quiet" onClick={() => setOpen(false)}>Cancel</button><button type="button" className="btn btn-primary" onClick={() => setOpen(false)}>Revoke key</button></>} />
  </>;
}

export function SegmentedButtonsDemo() {
  const [view, setView] = useState('chart');
  return <div className="ds-row">
    <Segmented label="View" value={view} onChange={setView} items={[{ value: 'chart', label: 'Chart' }, { value: 'table', label: 'Table' }, { value: 'map', label: 'Map', disabled: true }]} />
    <span className="muted small">Showing {view}</span>
  </div>;
}
