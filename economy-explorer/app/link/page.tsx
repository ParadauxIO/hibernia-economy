import Link from 'next/link';
import { buildMetadata } from '@/lib/metadata';
import { getViewer } from '@/lib/auth/viewer';
import { PrivacyGate } from '@/components/PrivacyGate';
import { LinkStartForm } from './link-form';

export const dynamic = 'force-dynamic';

export async function generateMetadata() {
  return buildMetadata({ title: "Link your account", description: "Link your in-game {server} identity to view your private economy data.", path: "/link" });
}

export default async function LinkPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const { callbackUrl } = await searchParams;
  const returnTo = typeof callbackUrl === 'string' && callbackUrl.startsWith('/') && !callbackUrl.startsWith('//') && !/[\\\u0000-\u001f\u007f]/.test(callbackUrl) ? callbackUrl : '/';
  const viewer = await getViewer();
  if (viewer.anon) {
    return (
      <PrivacyGate
        kind="login"
        title="Link your account"
        hint="Sign in first, then come back here to generate a code."
      />
    );
  }
  if (viewer.linked) {
    return (
      <>
        <div className="page-heading">
          <h1>Already linked</h1>
        </div>
        <div className="card">
          <div className="card-title">All set</div>
          <p style={{ padding: '4px 4px 8px', color: 'var(--fg-soft)' }}>
            Your Keycloak identity is linked to Minecraft UUID{' '}
            <span className="mono">{viewer.minecraftUuid ?? '(unknown)'}</span>.
          </p>
          <Link href={returnTo as '/'} className="btn btn-primary">Continue to explorer</Link>
        </div>
      </>
    );
  }
  return (
    <>
      <div className="page-heading">
        <h1>Link your account</h1>
        <span className="sub">connect your Minecraft UUID to this login</span>
      </div>
      <div className="card">
        <div className="card-title">How it works</div>
        <ol style={{ padding: '0 4px 8px 22px', color: 'var(--fg-soft)', lineHeight: 1.6 }}>
          <li>Generate a short code below.</li>
          <li>Join the Minecraft server and run <span className="mono">/treasuryapi ui link &lt;code&gt;</span> within 5 minutes.</li>
          <li>The plugin writes the link to the economy DB and your Keycloak attribute; refresh this page to see it picked up.</li>
        </ol>
      </div>
      <LinkStartForm />
    </>
  );
}
