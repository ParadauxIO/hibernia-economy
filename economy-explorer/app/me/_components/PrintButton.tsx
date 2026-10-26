'use client';

/** Opens the browser's print dialog; the statement's print stylesheet leaves just the statement (save as PDF). */
export function PrintButton({ label = 'Print or save as PDF' }: { label?: string }) {
  return <button type="button" className="btn" onClick={() => window.print()}>{label}</button>;
}
