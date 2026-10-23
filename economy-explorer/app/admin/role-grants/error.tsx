'use client';
export default function Error({ reset }: { reset: () => void }) {
  return <div role="alert"><p>Role grants could not be loaded.</p><button onClick={reset}>Try again</button></div>;
}
