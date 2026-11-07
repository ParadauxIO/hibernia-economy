'use client';

/** Opens the browser's print dialog; the entity stylesheet hides the chrome when printing. */
export function PrintButton({ label = 'Print' }: { label?: string }) {
  return <button type="button" className="btn btn-quiet" onClick={() => window.print()}>{label}</button>;
}
