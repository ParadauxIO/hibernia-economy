'use client';
// Header auth island: login button (anon) or name + role badges + log out.
// Client-side so the root layout doesn't read the session cookie. Renders a
// fixed-width placeholder while the viewer is loading to avoid layout shift.
import Link from 'next/link';
import { useViewer } from '@/components/ViewerContext';
import { loginAction } from '@/lib/actions/auth';

export function HeaderAuth() {
  const v = useViewer();

  if (v.loading) {
    return <span className="header-auth-loading" aria-hidden />;
  }

  if (v.anon) {
    return (
      <form action={loginAction}>
        <button type="submit" className="btn btn-primary">Log in</button>
      </form>
    );
  }

  return (
    <>
      <Link href="/me" className="header-me" prefetch={false}>
        {v.minecraftName ?? 'My data'}
        {v.role && v.role !== 'player' && <span className="badge">{v.role}</span>}
      </Link>
      <Link href="/api/auth/signout" className="btn" prefetch={false}>Log out</Link>
    </>
  );
}
