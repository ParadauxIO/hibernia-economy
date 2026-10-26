import Link from 'next/link';
import { AccessGate } from '@democracycraft/design-system';
import { signIn } from '@/lib/auth/authjs';

/**
 * Why a viewer can't open part of /gov, and how they could. `privacy-gate` is
 * kept as a class so the shared e2e gate checks find it.
 */
export function GovGate({ reason, title, description }: { reason: 'login' | 'permission' | 'unlinked' | 'scope'; title?: string; description?: string }) {
  if (reason === 'login') {
    return <div className="privacy-gate"><AccessGate kind="login" title={title ?? 'Sign in to open the government workspace'}
      description={description ?? 'Government officials see their departments’ accounts here.'}
      action={<form action={async () => { 'use server'; await signIn('keycloak'); }}><button type="submit" className="btn btn-primary">Log in</button></form>} /></div>;
  }
  if (reason === 'unlinked') {
    return <div className="privacy-gate"><AccessGate kind="link" title="Link your Minecraft account"
      description="Government access follows your in-game account relationships, so the explorer needs to know which player you are."
      action={<Link href="/link" className="btn btn-primary" prefetch={false}>Link your account</Link>} /></div>;
  }
  if (reason === 'scope') {
    return <div className="privacy-gate"><AccessGate kind="request" title={title ?? 'No government accounts in your scope'}
      description={description ?? 'You have government access, but no government account is shared with you yet.'}
      grants={['the owner of a government account', 'given viewer, member or authorizer access to one in game', 'in a LuckPerms group that has access to one']} /></div>;
  }
  return <div className="privacy-gate"><AccessGate kind="private" title={title ?? 'Government access required'}
    description={description ?? 'This workspace is for officials whose roles include government access.'} /></div>;
}
