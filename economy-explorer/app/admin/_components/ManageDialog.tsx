'use client';

import { useState, type ReactNode } from 'react';
import { Dialog } from '@democracycraft/design-system/popover';

/** A row's "Manage" button: the admin controls open in a dialog instead of crowding the table. */
export function ManageDialog({ label, title, description, children }: {
  /** Accessible name for the button, e.g. "Manage account 12". */
  label: string;
  title: ReactNode;
  description?: ReactNode;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" className="btn btn-quiet" aria-label={label} onClick={() => setOpen(true)}>Manage</button>
      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        title={title}
        description={description}
        size="md"
        actions={<button type="button" className="btn" onClick={() => setOpen(false)}>Close</button>}
      >
        {open && children}
      </Dialog>
    </>
  );
}
