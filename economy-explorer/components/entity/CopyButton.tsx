'use client';
// Copies a value (a link, an in-game command) and says so. When `value` is a
// path it is resolved against the current origin, so links copy as full URLs.

import { useState } from 'react';

export function CopyButton({ value, label, copiedLabel = 'Copied', className = 'btn btn-quiet', title }: {
  value: string;
  label: string;
  copiedLabel?: string;
  className?: string;
  title?: string;
}) {
  const [status, setStatus] = useState<'idle' | 'copied' | 'failed'>('idle');
  const copy = async () => {
    const text = value.startsWith('/') && !value.startsWith('//') ? new URL(value, window.location.origin).toString() : value;
    try {
      await navigator.clipboard.writeText(text);
      setStatus('copied');
    } catch {
      setStatus('failed');
    }
    window.setTimeout(() => setStatus('idle'), 2_000);
  };
  return <>
    <button type="button" className={className} onClick={copy} title={title ?? value}>
      {status === 'copied' ? copiedLabel : status === 'failed' ? 'Copy failed' : label}
    </button>
    <span className="sr-only" role="status">{status === 'copied' ? `${copiedLabel}: ${label}` : ''}</span>
  </>;
}
