'use client';

/** Opens the browser's print dialog; the report's print stylesheet hides the chrome. */
export function PrintButton() {
  return <button type="button" className="btn btn-quiet" onClick={() => window.print()}>Print or save as PDF</button>;
}
