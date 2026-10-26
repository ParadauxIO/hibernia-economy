'use client';
// The transaction detail drawer: the design system's modal Dialog, open while
// the URL has ?txn=<id>. Its content is rendered on the server and passed in;
// closing it (Escape, the backdrop, the Close button) drops ?txn from the URL so
// the list underneath stays exactly where it was.

import type { ReactNode } from 'react';
import { useRouter } from 'next/navigation';
import type { Route } from 'next';
import { Dialog } from '@democracycraft/design-system/popover';

export function TxnDrawer({ title, description, closeHref, actions, children }: {
  title: ReactNode;
  description?: ReactNode;
  closeHref: string;
  actions?: ReactNode;
  children: ReactNode;
}) {
  const router = useRouter();
  const close = () => router.replace(closeHref as Route, { scroll: false });
  return (
    <Dialog
      open
      onClose={close}
      title={title}
      description={description}
      size="lg"
      actions={<>
        {actions}
        <button type="button" className="btn" onClick={close}>Close</button>
      </>}
    >
      {children}
    </Dialog>
  );
}
