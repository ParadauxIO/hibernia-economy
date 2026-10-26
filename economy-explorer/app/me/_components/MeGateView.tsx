import { PrivacyGate } from '@/components/PrivacyGate';

const COPY = {
  private: { title: 'Permission required', hint: undefined },
  login: { title: 'My finances', hint: 'Log in to see your own money: balances, income and spending, statements, taxes and fines.' },
  link: { title: 'Link your Minecraft account', hint: 'You are signed in but not linked to a Minecraft player yet. Generate a code on the link page and run /treasuryapi ui link <code> in game.' },
} as const;

/** The /me gate for a viewer meGate() turned away. */
export function MeGateView({ kind, title }: { kind: 'private' | 'login' | 'link'; title?: string }) {
  const copy = COPY[kind];
  return <PrivacyGate kind={kind} title={kind === 'login' && title ? title : copy.title} {...(copy.hint ? { hint: copy.hint } : {})} />;
}
