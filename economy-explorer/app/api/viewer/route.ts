import { NextResponse } from 'next/server';
import { getViewer } from '@/lib/auth/viewer';
import { isStaff } from '@/lib/auth/access';

// Client-side viewer resolution for the header (login state, role badges, nav
// gating). Keeping this out of the root layout is what lets the layout — and so
// static pages like /docs — render without reading the session cookie. Per-user,
// so never cached at the HTTP layer.
export const dynamic = 'force-dynamic';

export async function GET() {
  const v = await getViewer();
  return NextResponse.json(
    {
      anon: v.anon,
      loggedIn: !v.anon,
      isAdmin: v.role === 'admin',
      // The read-only financial-oversight (viewer) capability — true for admin and
      // government too, since both imply it. Drives the header "viewer" badge for a
      // plain player who holds it via a group.
      isViewer: isStaff(v),
      role: v.anon ? null : v.role,
      minecraftName: v.anon ? null : v.minecraftName,
    },
    { headers: { 'Cache-Control': 'private, no-store' } },
  );
}
