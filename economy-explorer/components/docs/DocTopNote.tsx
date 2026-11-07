'use client';

import { useEffect, useState } from 'react';
import type { DocNotes } from '@/lib/docsNotes';

export function DocTopNote({ path }: { path: string }) {
  const [notes, setNotes] = useState<DocNotes>({});

  useEffect(() => {
    const controller = new AbortController();
    fetch('/api/docs/notes', { cache: 'no-store', signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error('Notes unavailable');
        return response.json() as Promise<DocNotes>;
      })
      .then((data) => { if (!controller.signal.aborted) setNotes(data); })
      .catch(() => { /* Optional notes must not prevent reading the guide. */ });
    return () => controller.abort();
  }, []);

  const note = notes[path];
  if (!note) return null;
  return (
    <aside className="doc-top-note" aria-label={note.title}>
      <strong>{note.title}</strong>
      <p>{note.text}</p>
      {note.link && <a href={note.link.href}>{note.link.label}</a>}
    </aside>
  );
}
