'use client';
// Client-side JSON download. Receives pre-computed data — must not pass
// functions across the RSC→client boundary. Export is gated to logged-in
// viewers.

import { Button } from '@democracycraft/design-system';
import { useIsLoggedIn } from '@/components/ViewerContext';

interface JsonButtonProps {
  filename: string;
  data: unknown;
  label?: string;
}

export function JsonButton({ filename, data, label = 'JSON' }: JsonButtonProps) {
  const loggedIn = useIsLoggedIn();
  if (!loggedIn) return null;
  function download() {
    const json = JSON.stringify(data, null, 2);
    const blob = new Blob([json], { type: 'application/json;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }
  return (
    <Button onClick={download} title="Download this page as JSON">
      {label}
    </Button>
  );
}
