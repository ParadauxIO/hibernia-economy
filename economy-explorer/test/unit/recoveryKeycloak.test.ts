import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { recoveryKeycloakConfig, replaceRecoveredPassword } from '@/lib/auth/recoveryKeycloak';
const request = vi.fn();
beforeEach(() => {
  vi.stubEnv('AUTH_KEYCLOAK_ISSUER', 'https://id.example/realms/minecraft');
  vi.stubEnv('RECOVERY_KEYCLOAK_CLIENT_ID', 'recovery');
  vi.stubEnv('RECOVERY_KEYCLOAK_CLIENT_SECRET', 'service-secret');
  vi.stubGlobal('fetch', request); request.mockReset();
});
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });
it('replaces only the approved subject password, permanent, with bounded requests and no cache', async () => {
  request.mockResolvedValueOnce(Response.json({ access_token: 'token' }))
    .mockResolvedValueOnce(Response.json({ enabled: true, username: '.bedrock' }))
    .mockResolvedValueOnce(new Response(null, { status: 204 }));
  expect(await replaceRecoveredPassword('approved/sub', 'my new password')).toBe('.bedrock');
  expect(request.mock.calls[2]![0]).toBe('https://id.example/admin/realms/minecraft/users/approved%2Fsub/reset-password');
  expect(JSON.parse(request.mock.calls[2]![1].body)).toEqual({ type: 'password', value: 'my new password', temporary: false });
  for (const [, options] of request.mock.calls) expect(options).toMatchObject({ cache: 'no-store', redirect: 'error', signal: expect.any(AbortSignal) });
});
it('does not reset a disabled account', async () => {
  request.mockResolvedValueOnce(Response.json({ access_token: 'token' })).mockResolvedValueOnce(Response.json({ enabled: false, username: '.bedrock' }));
  await expect(replaceRecoveredPassword('sub', 'my new password')).rejects.toThrow('could not be confirmed');
  expect(request).toHaveBeenCalledTimes(2);
});
it.each([400, 403, 500])('sanitizes an upstream rejection (%i) and never retries', async status => {
  request.mockResolvedValueOnce(Response.json({ access_token: 'token' }))
    .mockResolvedValueOnce(Response.json({ enabled: true, username: '.bedrock' }))
    .mockResolvedValueOnce(new Response('my new password secret internal error', { status }));
  await expect(replaceRecoveredPassword('sub', 'my new password')).rejects.toThrow(/^The password change could not be confirmed\./);
  expect(request).toHaveBeenCalledTimes(3);
});
it.each(['http://id.example/realms/minecraft', 'https://id.example/realms/minecraft?x=1', 'https://id.example/other'])('rejects an unsafe or invalid issuer %s', issuer => {
  vi.stubEnv('AUTH_KEYCLOAK_ISSUER', issuer); expect(() => recoveryKeycloakConfig()).toThrow('unavailable');
});
