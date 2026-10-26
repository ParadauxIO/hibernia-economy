import './me.css';
import type { ReactNode } from 'react';
import { getViewer } from '@/lib/auth/viewer';
import { meGate } from '@/lib/services/me';
import { MeSidebar } from './_components/MeSidebar';

/**
 * The /me workspace (spec §8). The sidebar is presentation only: every page
 * runs meGate itself (permission, then anonymous, then unlinked), because a
 * layout is not re-rendered on every navigation and is not an access check.
 */
export default async function MeLayout({ children }: { children: ReactNode }) {
  const gate = meGate(await getViewer());
  if (!gate.ok) return <>{children}</>;
  return <MeSidebar>{children}</MeSidebar>;
}
