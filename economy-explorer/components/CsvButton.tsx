'use client';
// Client-side CSV download. Rows are pre-computed by the RSC parent —
// functions can't cross the RSC→client boundary, so we accept the final
// (header[], row-of-strings[][]) shape. Export is gated to logged-in viewers.
// Cell escaping (formula injection, quoting) is shared with the full-export
// route through lib/export/csv.ts.

import { Button } from '@democracycraft/design-system';
import { useIsLoggedIn } from '@/components/ViewerContext';
import { csvLine } from '@/lib/export/csv';

interface CsvButtonProps {
  filename: string;
  headers: string[];
  rows: (string | number | null | undefined)[][];
  label?: string;
}

export function CsvButton({ filename, headers, rows, label = 'CSV' }: CsvButtonProps) {
  const loggedIn = useIsLoggedIn();
  if (!loggedIn) return null;
  function download() {
    const csv = `﻿${csvLine(headers)}\n${rows.map(csvLine).join('\n')}`;
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
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
    <Button onClick={download} disabled={rows.length === 0} title="Download this page as CSV">
      {label}
    </Button>
  );
}
