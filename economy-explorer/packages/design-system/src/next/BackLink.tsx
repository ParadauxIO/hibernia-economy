import Link from 'next/link';
import type { Route } from 'next';

export function BackLink({ href, label }: { href: string; label: string }) {
  return (
    <Link href={href as Route} className="back-link" prefetch={false}>
      ← {label}
    </Link>
  );
}
