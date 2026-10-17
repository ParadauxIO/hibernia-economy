import { canAccessAdmin } from '@/lib/auth/admin';
import { getViewer } from '@/lib/auth/viewer';
import { PrivacyGate } from '@/components/PrivacyGate';
import { AdminNav } from '@/components/AdminNav';

// Gates /admin/* to users with at least one tool permission and renders navigation.
// Individual pages keep their own gate as defence-in-depth.
export default async function AdminLayout({ children }: { children: React.ReactNode }) {
  const viewer = await getViewer();
  if (viewer.anon) return <PrivacyGate kind="login" title="Admin" hint="Sign in to use the admin tools available to your roles." />;
  if (!canAccessAdmin(viewer)) return <PrivacyGate kind="private" title="Administrative permission required" />;
  return (
    <>
      <AdminNav />
      {children}
    </>
  );
}
