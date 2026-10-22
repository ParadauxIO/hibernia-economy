import { describe, expect, it, vi } from 'vitest';
import type { NextAuthConfig } from 'next-auth';

const captured = vi.hoisted(() => ({ config: null as NextAuthConfig | null }));
vi.mock('next-auth', () => ({ default: (config: NextAuthConfig) => {
  captured.config = config;
  return { auth: vi.fn(), handlers: {}, signIn: vi.fn(), signOut: vi.fn() };
} }));
import '@/lib/auth/authjs';

describe('OIDC identity in the stateless session', () => {
  it('uses the verified provider subject instead of the generated Auth.js user ID and retains it on later requests', async () => {
    const jwt = captured.config!.callbacks!.jwt!;
    const token = await jwt({
      token: { sub: 'generated-local-user-id' }, user: { id: 'generated-local-user-id' },
      account: { type: 'oidc', provider: 'keycloak', providerAccountId: 'stable-keycloak-subject',
        access_token: 'must-not-be-retained', refresh_token: 'must-not-be-retained' },
      profile: { sub: 'stable-keycloak-subject', minecraft_uuid: 'player-uuid', minecraft_name: 'Player' },
    });
    expect(token).toEqual({ sub: 'stable-keycloak-subject', minecraftUuid: 'player-uuid', minecraftName: 'Player' });
    expect(await jwt({ token: token!, user: { id: 'ignored' }, account: null })).toEqual(token);
  });
});
