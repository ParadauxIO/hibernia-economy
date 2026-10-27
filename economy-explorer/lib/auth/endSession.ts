import 'server-only';

// Federated logout: after clearing our own session cookie, send the browser to
// the IdP's OIDC RP-initiated logout endpoint so the Keycloak SSO session ends
// too. Otherwise "Log out" followed by "Log in" signs straight back in.
//
// The session deliberately stores no Keycloak tokens (lib/auth/authjs.ts), so
// there is no id_token_hint. Keycloak accepts client_id + post_logout_redirect_uri
// instead; without the hint it asks the user to confirm the logout once. The
// redirect URI must be listed in the client's "Valid post logout redirect URIs".

/** The end_session_endpoint URL with client_id and post_logout_redirect_uri. Pure. */
export function buildEndSessionUrl(endpoint: string, clientId: string, postLogoutRedirectUri: string): `${string}:${string}` {
  const url = new URL(endpoint);
  url.searchParams.set('client_id', clientId);
  url.searchParams.set('post_logout_redirect_uri', postLogoutRedirectUri);
  // URL parsing guarantees an absolute, protocol-prefixed destination.
  return `${url.protocol.slice(0, -1)}:${url.toString().slice(url.protocol.length)}`;
}

/** Keycloak's fixed path, for when discovery is unreachable. */
export function keycloakLogoutEndpoint(issuer: string): string {
  return `${issuer.replace(/\/+$/, '')}/protocol/openid-connect/logout`;
}

let discovered: { issuer: string; endpoint: string } | null = null;

/** end_session_endpoint from the issuer's discovery document, cached per process. */
export async function endSessionEndpoint(issuer: string): Promise<string> {
  if (discovered?.issuer === issuer) return discovered.endpoint;
  try {
    const res = await fetch(`${issuer.replace(/\/+$/, '')}/.well-known/openid-configuration`, {
      signal: AbortSignal.timeout(2000),
      cache: 'no-store',
    });
    if (res.ok) {
      const doc = (await res.json()) as { end_session_endpoint?: unknown };
      if (typeof doc.end_session_endpoint === 'string') {
        discovered = { issuer, endpoint: doc.end_session_endpoint };
        return doc.end_session_endpoint;
      }
    }
  } catch {
    /* fall through to the Keycloak convention */
  }
  return keycloakLogoutEndpoint(issuer);
}

/** The site origin Keycloak sends the user back to, without a trailing slash. */
export function postLogoutRedirectUri(env: NodeJS.ProcessEnv = process.env): string | null {
  // Same precedence as lib/metadata siteUrl().
  const base = env.SITE_URL?.trim() || env.AUTH_URL?.trim();
  if (!base) return null;
  try {
    return new URL(base).origin;
  } catch {
    return null;
  }
}

/** Where to send the browser after our session is cleared, or null for a local-only logout. */
export async function federatedLogoutUrl(env: NodeJS.ProcessEnv = process.env): Promise<`${string}:${string}` | null> {
  const issuer = env.AUTH_KEYCLOAK_ISSUER?.trim();
  const clientId = env.AUTH_KEYCLOAK_ID?.trim();
  const back = postLogoutRedirectUri(env);
  if (!issuer || !clientId || !back) return null;
  return buildEndSessionUrl(await endSessionEndpoint(issuer), clientId, back);
}

/** Test seam. */
export function resetEndSessionCacheForTests(): void {
  discovered = null;
}
