import 'server-only';
import { cookies } from 'next/headers';

/**
 * Resolve the active brand theme: ?theme= cookie override (set by the proxy)
 * then the THEME env default. Shared by the layout and any page that needs
 * server-identity (e.g. the BlueMap base URL on the shops pages).
 */
export async function resolveTheme(): Promise<'blue' | 'red'> {
  const c = await cookies();
  const ck = c.get('theme')?.value.toLowerCase().trim();
  if (ck === 'red' || ck === 'blue') return ck;
  return (process.env.THEME ?? 'blue').toLowerCase().trim() === 'red' ? 'red' : 'blue';
}

/** The user's surface-mode *choice*. 'system' follows the OS preference and is
 * resolved client-side (see the no-FOUC script in the layout); the server can't
 * know the OS preference, so SSR falls back to dark for it. Default: dark
 * (unchanged for cookie-less visitors — 'system' is opt-in via the toggle). */
export type ModeChoice = 'system' | 'dark' | 'light';

export async function resolveModeChoice(): Promise<ModeChoice> {
  const c = await cookies();
  const v = c.get('mode')?.value.toLowerCase().trim();
  return v === 'light' || v === 'system' ? v : 'dark';
}

/** Concrete surface mode for the SSR `data-mode` attribute. 'system' resolves
 * to dark on the server; the client script corrects it before first paint. */
export async function resolveMode(): Promise<'dark' | 'light'> {
  return (await resolveModeChoice()) === 'light' ? 'light' : 'dark';
}
