import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Viewer } from '@/lib/auth/viewer';

vi.mock('@/lib/auth/viewer', () => ({ getViewer: vi.fn() }));

const ALEX = '0b5a1c2d-3e4f-4a5b-8c6d-7e8f9a0b1c2d';
const signedIn = (minecraftUuid: string | null): Viewer => ({
  anon: false, keycloakSub: 'kc', minecraftUuid, minecraftName: 'Alex', linked: true, role: 'admin', capabilities: ['admin'], permissions: [],
});

async function load() {
  vi.resetModules();
  vi.stubEnv('TREASURY_API_BASE_URL', 'https://treasury.test/');
  vi.stubEnv('TREASURY_ADMIN_TOKEN', 'service-token');
  const viewer = await import('@/lib/auth/viewer');
  const treasury = await import('@/lib/treasury');
  return { getViewer: vi.mocked(viewer.getViewer), treasury };
}

const fetchMock = vi.fn();
beforeEach(() => {
  fetchMock.mockReset().mockResolvedValue(new Response(JSON.stringify({ txnId: 9 }), { status: 200 }));
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

function sentHeaders(): Record<string, string> {
  return (fetchMock.mock.calls[0]?.[1] as RequestInit).headers as Record<string, string>;
}

describe('X-Acting-Player on treasury admin calls', () => {
  it('names the signed-in player on every admin call, with the service token', async () => {
    const { getViewer, treasury } = await load();
    getViewer.mockResolvedValue(signedIn(ALEX.toUpperCase()));
    await treasury.adminTransfer({ fromAccountId: 1, toAccountId: 2, amount: '5.00', memo: 'm' });
    expect(fetchMock.mock.calls[0]?.[0]).toBe('https://treasury.test/api/v1/admin/transfers');
    expect(sentHeaders()).toMatchObject({ authorization: 'Bearer service-token', 'X-Acting-Player': ALEX });

    fetchMock.mockClear().mockResolvedValue(new Response(null, { status: 204 }));
    await treasury.revokeApiKey(3);
    expect(sentHeaders()['X-Acting-Player']).toBe(ALEX);
  });

  it('omits the header when there is no linked player, and never sends a malformed one', async () => {
    const { getViewer, treasury } = await load();
    getViewer.mockResolvedValue(signedIn(null));
    await treasury.renameAccount(1, 'x');
    expect(sentHeaders()).not.toHaveProperty('X-Acting-Player');

    fetchMock.mockClear().mockResolvedValue(new Response('{}', { status: 200 }));
    getViewer.mockResolvedValue(signedIn('not-a-uuid\r\nX-Evil: 1'));
    await treasury.renameAccount(1, 'x');
    expect(sentHeaders()).not.toHaveProperty('X-Acting-Player');

    fetchMock.mockClear().mockResolvedValue(new Response('{}', { status: 200 }));
    getViewer.mockRejectedValue(new Error('outside a request'));
    await treasury.renameAccount(1, 'x');
    expect(sentHeaders()).not.toHaveProperty('X-Acting-Player');
  });

  it('surfaces the API error message', async () => {
    const { getViewer, treasury } = await load();
    getViewer.mockResolvedValue(signedIn(ALEX));
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ error: 'INVALID_ACTING_PLAYER', message: 'Bad header.' }), { status: 400 }));
    await expect(treasury.archiveAccount(1)).rejects.toThrow('Bad header.');
  });
});
