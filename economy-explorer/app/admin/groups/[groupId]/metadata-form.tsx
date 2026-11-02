'use client';
import { useState, useTransition } from 'react';
import { useRouter } from 'next/navigation';
import { updateGroupMetadataAction } from '@/lib/actions/admin';
export function GroupMetadataForm({ groupId, name, description }: { groupId: number; name: string; description: string | null }) {
  const router = useRouter();
  const [value, setValue] = useState(name);
  const [details, setDetails] = useState(description ?? '');
  const [pending, start] = useTransition();
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  return <form onSubmit={e => {
    e.preventDefault(); setMessage(null);
    start(async () => {
      const r = await updateGroupMetadataAction(groupId, { name: value, description: details });
      setMessage({ ok: r.ok, text: r.ok ? 'Group details saved.' : r.error ?? 'Save failed.' });
      if (r.ok) router.refresh();
    });
  }}>
    <label className="field"><span className="field-label">Group name</span><input className="input" value={value} onChange={e => setValue(e.target.value)} required maxLength={64} /></label>
    <label className="field"><span className="field-label">Description</span><textarea className="input" value={details} onChange={e => setDetails(e.target.value)} maxLength={255} /></label>
    <button className="btn" disabled={pending || !value.trim() || (value === name && details === (description ?? ''))}>{pending ? 'Saving…' : 'Save group details'}</button>
    {message && <p role={message.ok ? 'status' : 'alert'}>{message.text}</p>}
  </form>;
}
