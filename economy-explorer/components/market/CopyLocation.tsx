"use client";
import { useState } from 'react';
export function CopyLocation({ location }: { location: string }) {
  const [status, setStatus] = useState('');
  return <><button className="btn" type="button" aria-label={`Copy location ${location}`} onClick={async () => {
    try { await navigator.clipboard.writeText(location); setStatus('Copied'); }
    catch { setStatus('Copy unavailable — select the location text'); }
  }}>Copy location</button><span role="status" className="small">{status}</span></>;
}
