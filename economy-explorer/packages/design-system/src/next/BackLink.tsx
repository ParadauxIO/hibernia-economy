import type { Route } from 'next';
import Link from 'next/link';

export function BackLink<T extends string>({ href, label }: { href: Route<T>; label: string }) {
  return (
    <Link href={href} className="back-link" prefetch={false}>
      ← {label}
    </Link>
  );
}
