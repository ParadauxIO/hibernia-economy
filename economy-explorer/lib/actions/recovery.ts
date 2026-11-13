'use server';
import { createHash, randomBytes, randomInt } from 'node:crypto';
import { cookies, headers } from 'next/headers';
import { requireFeatureEnabled } from '@/lib/featureFlags';
import { waitForRedisReady } from '@/lib/redis';
import { checkAccountRateLimit } from '@/lib/requestRateLimit';
import { createRequestBlocker } from '@/lib/requestBlocking';
import { startRecovery, recoveryStatus, claimRecovery } from '@/lib/sql/recovery';
import { recoveryKeycloakConfig, replaceRecoveredPassword } from '@/lib/auth/recoveryKeycloak';

const COOKIE = 'explorer-recovery';
const ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const hash = (value: string) => createHash('sha256').update(value).digest('hex');
const validSecret = (value: string | undefined): value is string => !!value && /^[a-f0-9]{64}$/.test(value);
export type RecoveryResult = {
  state: 'pending' | 'approved' | 'expired' | 'complete' | 'error';
  code?: string;
  username?: string;
  error?: string;
};

async function guard(operation: 'start' | 'status' | 'finish') {
  await requireFeatureEnabled('explorer.browser_recovery');
  recoveryKeycloakConfig();
  // Trust only the same ingress identity used by the request blocker. Without
  // trusted ingress, all clients share one conservative bucket; never trust XFF.
  const ip = createRequestBlocker(process.env)(await headers()).ip || 'shared';
  await waitForRedisReady();
  const limited = await checkAccountRateLimit(`recovery:${operation}:${ip}`, { limit: operation === 'status' ? '30' : '5' });
  if (limited) throw new Error(limited.status === 429 ? 'Too many attempts. Wait a minute and try again.' : 'Recovery is temporarily unavailable.');
}
function failure(error: unknown): RecoveryResult {
  // Only controlled messages leave this boundary; SQL and upstream details stay private.
  const message = error instanceof Error && [
    'Too many attempts. Wait a minute and try again.', 'Recovery is temporarily unavailable.',
    'Recovery is unavailable.',
    'The password change could not be confirmed. Try logging in with your new password; otherwise start recovery again. Choose a password that meets the account password policy.',
  ].includes(error.message) ? error.message : 'Recovery is temporarily unavailable. Please try again later.';
  return { state: 'error', error: message };
}

export async function beginRecovery(): Promise<RecoveryResult> {
  try {
    await guard('start');
    const jar = await cookies();
    const previous = jar.get(COOKIE)?.value;
    const secret = randomBytes(32).toString('hex');
    const code = Array.from({ length: 10 }, () => ALPHABET[randomInt(ALPHABET.length)]).join('');
    await startRecovery(hash(secret), hash(code), validSecret(previous) ? hash(previous) : undefined);
    jar.set(COOKIE, secret, { httpOnly: true, secure: process.env.NODE_ENV === 'production', sameSite: 'strict', path: '/recover', maxAge: 300 });
    return { state: 'pending', code };
  } catch (error) { return failure(error); }
}

export async function checkRecovery(): Promise<RecoveryResult> {
  try {
    await guard('status');
    const secret = (await cookies()).get(COOKIE)?.value;
    if (!validSecret(secret)) return { state: 'expired' };
    return { state: await recoveryStatus(hash(secret)) };
  } catch (error) { return failure(error); }
}

export async function finishRecovery(form: FormData): Promise<RecoveryResult> {
  try {
    await guard('finish');
    const password = form.get('password');
    if (typeof password !== 'string' || password.length < 12 || password.length > 128
      || password !== form.get('confirmPassword')) {
      return { state: 'error', error: 'Use 12–128 characters and enter the same password twice.' };
    }
    const jar = await cookies();
    const secret = jar.get(COOKIE)?.value;
    if (!validSecret(secret)) return { state: 'expired' };
    const subject = await claimRecovery(hash(secret));
    if (!subject) return { state: 'expired' };
    // Remove even when Keycloak fails: a consumed request must never be retried.
    jar.set(COOKIE, '', { httpOnly: true, secure: process.env.NODE_ENV === 'production', sameSite: 'strict', path: '/recover', maxAge: 0 });
    const username = await replaceRecoveredPassword(subject, password);
    return { state: 'complete', username };
  } catch (error) { return failure(error); }
}
