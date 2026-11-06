'use client';
// The Admin button in the top bar, for viewers who hold at least one admin
// tool. Phones get the same entry in the menu sheet instead (HeaderMenu).

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { useViewer } from '@/components/ViewerContext';

export const isAdminPath = (path: string) => path === '/admin' || path.startsWith('/admin/');

export function HeaderAdmin() {
  const path = usePathname();
  const { isAdmin } = useViewer();
  if (!isAdmin) return null;
  return (
    <Link href="/admin" prefetch={false} className="shell-admin shell-desktop-only" aria-current={isAdminPath(path) ? (path === '/admin' ? 'page' : 'true') : undefined}>
      Admin
    </Link>
  );
}
