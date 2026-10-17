'use client';
// Client-side CSV download. Rows are pre-computed by the RSC parent —
// functions can't cross the RSC→client boundary, so we accept the final
// (header[], row-of-strings[][]) shape. Export is gated to logged-in viewers.

import { useIsLoggedIn } from '@/components/ViewerContext';

interface CsvButtonProps {
  filename: string;
  headers: string[];
  rows: (string | number | null | undefined)[][];
  label?: string;
}

export function CsvButton({ filename, headers, rows, label = 'CSV' }: CsvButtonProps) {
  const loggedIn = useIsLoggedIn();
  if (!loggedIn) return null;
  function escape(v: string | number | null | undefined): string {
    if (v === null || v === undefined) return '';
    let s = String(v);
    // Neutralize CSV/formula injection: a cell starting with one of these is
    // interpreted as a formula by Excel/Sheets. Exported fields include
    // attacker-controllable Minecraft strings (display names, txn messages,
    // item/player names), so prefix with ' to force it to a literal.
    if (/^[=+\-@\t\r]/.test(s)) {
      s = `'${s}`;
    }
    if (s.includes(',') || s.includes('"') || s.includes('\n')) {
      return `"${s.replace(/"/g, '""')}"`;
    }
    return s;
  }
  function download() {
    const headerLine = headers.map(escape).join(',');
    const body = rows.map((r) => r.map(escape).join(',')).join('\n');
    const csv = `﻿${headerLine}\n${body}`;
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
    <button type="button" className="btn" onClick={download} disabled={rows.length === 0} title="Download this page as CSV">
      {label}
    </button>
  );
}
