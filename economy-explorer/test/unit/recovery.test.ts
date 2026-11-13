import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createHash } from 'node:crypto';
const mocks = vi.hoisted(() => ({
  cookies: new Map<string, string>(), setCookie: vi.fn(),
  guard: vi.fn(), limit: vi.fn(), start: vi.fn(), status: vi.fn(), claim: vi.fn(), reset: vi.fn(), config: vi.fn(),
}));
vi.mock('next/headers', () => ({
  cookies: async () => ({ get: (key: string) => ({ value: mocks.cookies.get(key) }), set: mocks.setCookie }),
  headers: async () => new Headers({ 'x-forwarded-for': 'untrusted-address' }),
}));
vi.mock('@/lib/redis', () => ({ waitForRedisReady: async () => {} }));
vi.mock('@/lib/featureFlags', () => ({ requireFeatureEnabled: mocks.guard }));
vi.mock('@/lib/requestRateLimit', () => ({ checkAccountRateLimit: mocks.limit }));
vi.mock('@/lib/sql/recovery', () => ({ startRecovery: mocks.start, recoveryStatus: mocks.status, claimRecovery: mocks.claim }));
vi.mock('@/lib/auth/recoveryKeycloak', () => ({ replaceRecoveredPassword: mocks.reset, recoveryKeycloakConfig: mocks.config }));
import { beginRecovery, checkRecovery, finishRecovery } from '@/lib/actions/recovery';
const hash = (s: string) => createHash('sha256').update(s).digest('hex');
function passwordForm(password = 'a long new password') {
  const form = new FormData(); form.set('password', password); form.set('confirmPassword', password); return form;
}
beforeEach(() => {
  vi.resetAllMocks(); mocks.cookies.clear(); mocks.limit.mockResolvedValue(null);
  mocks.claim.mockResolvedValue('sender-sub'); mocks.reset.mockResolvedValue('.bedrock');
});
describe('browser-bound password recovery', () => {
  it('stores hashes only and gives the browser an HttpOnly cookie distinct from the short code', async () => {
    const result = await beginRecovery();
    expect(result).toMatchObject({ state: 'pending', code: expect.stringMatching(/^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{10}$/) });
    const [, secret, options] = mocks.setCookie.mock.calls[0]!;
    expect(secret).toMatch(/^[a-f0-9]{64}$/);
    expect(options).toMatchObject({ httpOnly: true, sameSite: 'strict', path: '/recover', maxAge: 300 });
    expect(mocks.start).toHaveBeenCalledWith(hash(secret), hash(result.code!), undefined);
    expect(JSON.stringify(result)).not.toContain(secret);
    expect(mocks.limit).toHaveBeenCalledWith('recovery:start:shared', { limit: '5' });
  });
  it('requires a browser cookie; knowing the displayed code cannot complete or inspect a request', async () => {
    expect(await finishRecovery(passwordForm())).toEqual({ state: 'expired' });
    mocks.cookies.set('explorer-recovery', 'ABCDEFGHJK');
    expect(await checkRecovery()).toEqual({ state: 'expired' });
    expect(await finishRecovery(passwordForm())).toEqual({ state: 'expired' });
    expect(mocks.claim).not.toHaveBeenCalled(); expect(mocks.reset).not.toHaveBeenCalled();
  });
  it.each(['pending', 'expired'] as const)('never resets an unapproved or expired request (%s)', async state => {
    mocks.cookies.set('explorer-recovery', 'a'.repeat(64)); mocks.status.mockResolvedValue(state); mocks.claim.mockResolvedValue(null);
    expect((await checkRecovery()).state).toBe(state);
    expect(await finishRecovery(passwordForm())).toEqual({ state: 'expired' });
    expect(mocks.reset).not.toHaveBeenCalled();
  });
  it('uses only the claimed account, consumes its cookie, and returns the actual login username', async () => {
    mocks.cookies.set('explorer-recovery', 'a'.repeat(64));
    const form = passwordForm(); form.set('subject', 'attacker');
    expect(await finishRecovery(form)).toEqual({ state: 'complete', username: '.bedrock' });
    expect(mocks.claim).toHaveBeenCalledWith(hash('a'.repeat(64)));
    expect(mocks.reset).toHaveBeenCalledWith('sender-sub', 'a long new password');
    expect(mocks.setCookie).toHaveBeenCalledWith('explorer-recovery', '', expect.objectContaining({ maxAge: 0, path: '/recover' }));
  });
  it('rejects mismatched and out-of-bounds passwords before consuming the approval', async () => {
    mocks.cookies.set('explorer-recovery', 'a'.repeat(64));
    const mismatch = passwordForm(); mismatch.set('confirmPassword', 'other password');
    for (const form of [mismatch, passwordForm('short'), passwordForm('a'.repeat(129))]) expect((await finishRecovery(form)).state).toBe('error');
    expect(mocks.claim).not.toHaveBeenCalled(); expect(mocks.reset).not.toHaveBeenCalled();
  });
  it('fails closed before DB work when disabled, unconfigured or rate limited', async () => {
    mocks.guard.mockRejectedValueOnce(new Error('disabled'));
    expect((await beginRecovery()).state).toBe('error');
    mocks.config.mockImplementationOnce(() => { throw new Error('unconfigured'); });
    expect((await beginRecovery()).state).toBe('error');
    mocks.limit.mockResolvedValue({ status: 503 });
    expect((await beginRecovery()).state).toBe('error');
    expect(mocks.start).not.toHaveBeenCalled();
  });
  it('never leaks DB/upstream errors and clears a consumed cookie even on failure', async () => {
    mocks.cookies.set('explorer-recovery', 'a'.repeat(64)); mocks.reset.mockRejectedValue(new Error('secret credential response'));
    const result = await finishRecovery(passwordForm());
    expect(result.state).toBe('error'); expect(JSON.stringify(result)).not.toContain('credential');
    expect(mocks.setCookie).toHaveBeenCalledWith('explorer-recovery', '', expect.objectContaining({ maxAge: 0 }));
    expect(mocks.reset).toHaveBeenCalledOnce();
  });
});
