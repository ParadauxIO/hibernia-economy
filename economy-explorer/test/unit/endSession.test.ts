import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { buildEndSessionUrl, endSessionEndpoint, federatedLogoutUrl, keycloakLogoutEndpoint, postLogoutRedirectUri, resetEndSessionCacheForTests } from '@/lib/auth/endSession';

const ISSUER = 'https://auth.example.net/realms/minecraft';

beforeEach(() => resetEndSessionCacheForTests());
afterEach(() => vi.unstubAllGlobals());

describe('federated logout URL', () => {
  it('uses client_id + post_logout_redirect_uri (no tokens are stored, so no id_token_hint)', () => {
    const url = new URL(buildEndSessionUrl(`${ISSUER}/protocol/openid-connect/logout`, 'treasury-explorer', 'https://economy.example.net'));
    expect(url.origin + url.pathname).toBe(`${ISSUER}/protocol/openid-connect/logout`);
    expect(Object.fromEntries(url.searchParams)).toEqual({ client_id: 'treasury-explorer', post_logout_redirect_uri: 'https://economy.example.net' });
  });

  it('sends the user back to the site origin without a trailing slash', () => {
    expect(postLogoutRedirectUri({ SITE_URL: 'https://economy.example.net/', AUTH_URL: 'https://other.example' } as unknown as NodeJS.ProcessEnv)).toBe('https://economy.example.net');
    expect(postLogoutRedirectUri({ AUTH_URL: 'https://economy.example.net/base' } as unknown as NodeJS.ProcessEnv)).toBe('https://economy.example.net');
    expect(postLogoutRedirectUri({} as unknown as NodeJS.ProcessEnv)).toBeNull();
    expect(postLogoutRedirectUri({ SITE_URL: 'not a url' } as unknown as NodeJS.ProcessEnv)).toBeNull();
  });

  it('reads end_session_endpoint from discovery once', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ end_session_endpoint: 'https://auth.example.net/logout-here' })));
    vi.stubGlobal('fetch', fetchMock);
    expect(await endSessionEndpoint(ISSUER)).toBe('https://auth.example.net/logout-here');
    expect(await endSessionEndpoint(ISSUER)).toBe('https://auth.example.net/logout-here');
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(`${ISSUER}/.well-known/openid-configuration`, expect.anything());
  });

  it('falls back to the Keycloak path when discovery fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new Error('offline'); }));
    expect(await endSessionEndpoint(`${ISSUER}/`)).toBe(keycloakLogoutEndpoint(ISSUER));
  });

  it('is a local-only logout when the issuer, client or site is not configured', async () => {
    expect(await federatedLogoutUrl({ AUTH_KEYCLOAK_ID: 'c', SITE_URL: 'https://x.example' } as unknown as NodeJS.ProcessEnv)).toBeNull();
    expect(await federatedLogoutUrl({ AUTH_KEYCLOAK_ISSUER: ISSUER, SITE_URL: 'https://x.example' } as unknown as NodeJS.ProcessEnv)).toBeNull();
    expect(await federatedLogoutUrl({ AUTH_KEYCLOAK_ISSUER: ISSUER, AUTH_KEYCLOAK_ID: 'c' } as unknown as NodeJS.ProcessEnv)).toBeNull();
  });

  it('builds the full URL from the environment', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}')));
    const url = await federatedLogoutUrl({ AUTH_KEYCLOAK_ISSUER: ISSUER, AUTH_KEYCLOAK_ID: 'treasury-explorer', SITE_URL: 'https://economy.example.net' } as unknown as NodeJS.ProcessEnv);
    expect(url).toBe(`${ISSUER}/protocol/openid-connect/logout?client_id=treasury-explorer&post_logout_redirect_uri=https%3A%2F%2Feconomy.example.net`);
  });
});
