'use client';

import { useEffect, useRef } from 'react';
import { usePathname } from 'next/navigation';

const SECTIONS = new Set(['accounts', 'players', 'firms', 'market', 'chestshop', 'transactions', 'economy', 'me', 'gov', 'admin', 'search', 'docs']);
const section = (path: string) => path === '/' ? 'overview' : SECTIONS.has(path.split('/')[1] ?? '') ? path.split('/')[1]! : 'other';

/** Follow-through goals contain section names only, never entity IDs or query filters. */
export function ExplorerAnalytics() {
  const pathname = usePathname();
  const previous = useRef(pathname);
  useEffect(() => {
    if (previous.current === pathname) return;
    const from = section(previous.current);
    previous.current = pathname;
    const umami = (window as Window & { umami?: { track: (event: string, data: Record<string, string>) => void } }).umami;
    umami?.track('explorer-follow-through', { from, to: section(pathname) });
  }, [pathname]);
  return null;
}
