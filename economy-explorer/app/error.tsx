'use client';
// Route-segment error boundary (renders inside the root layout, so the header/
// footer chrome stays). Auth/forbidden flows render <PrivacyGate> inline rather
// than throwing, so this only handles genuine unexpected errors. Layout-level
// failures are caught by global-error.tsx.
import { useEffect } from 'react';
import Link from 'next/link';

export default function Error({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  useEffect(() => {
    console.error('[error]', error);
  }, [error]);

  return (
    <div className="explorer-error">
      <h1>Something went wrong</h1>
      <p>This page could not load. Please try again, or continue browsing another section.</p>
      <button type="button" className="btn btn-primary" onClick={() => reset()}>Try again</button>
      <p><Link href="/">Economy overview</Link> · <Link href="/chestshop/shops">Find shops</Link></p>
    </div>
  );
}
