import Link from 'next/link';
import { getViewer } from '@/lib/auth/viewer';
import { loginReturningTo } from '@/lib/actions/entity';

export const dynamic = 'force-dynamic';

export default async function AccessPage({ searchParams }: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { callbackUrl } = await searchParams;
  const returnTo = typeof callbackUrl === 'string' && callbackUrl.startsWith('/')
    && !callbackUrl.startsWith('//') && !/[\\\u0000-\u001f\u007f]/.test(callbackUrl) ? callbackUrl : '/';
  const viewer = await getViewer();
  const link = '/link?' + new URLSearchParams({ callbackUrl: returnTo });
  return <section className="privacy-gate privacy-gate--card explorer-access">
    <div className="privacy-gate-icon" aria-hidden="true">
      <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <rect x="5" y="10" width="14" height="11" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3" />
      </svg>
    </div>
    <p className="eyebrow">Your economy, connected</p>
    <h1>There’s more to explore</h1>
    <p className="privacy-gate-hint">Explore accounts, follow transactions, and see the details behind the economy.
      Create an account or log in, then verify your Minecraft identity in game to continue.</p>
    <ol className="explorer-access-steps">
      <li><strong>1</strong> Create an account or log in</li>
      <li><strong>2</strong> Verify your account in game</li>
      <li><strong>3</strong> Explore more</li>
    </ol>
    <div className="privacy-gate-actions">
      {viewer.anon
        ? <form action={loginReturningTo.bind(null, link)}><button className="btn btn-primary" type="submit">Log in or create an account</button></form>
        : <Link className="btn btn-primary" href={link as '/link'}>Verify your account in game</Link>}
      <Link className="btn" href="/docs">Read the docs</Link>
    </div>
  </section>;
}
