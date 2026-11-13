import 'server-only';

/** Dedicated server-side credentials, separate from the public OIDC login client. */
export function recoveryKeycloakConfig() {
  const issuer = process.env.AUTH_KEYCLOAK_ISSUER;
  const clientId = process.env.RECOVERY_KEYCLOAK_CLIENT_ID;
  const secret = process.env.RECOVERY_KEYCLOAK_CLIENT_SECRET;
  if (!issuer || !clientId || !secret) throw new Error('Recovery is unavailable.');
  const url = new URL(issuer);
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash
    || !/\/realms\/[^/]+\/?$/.test(url.pathname)) throw new Error('Recovery is unavailable.');
  const base = url.href.replace(/\/$/, '');
  return { issuer: base, admin: base.replace(/\/realms\/([^/]+)$/, '/admin/realms/$1'), clientId, secret };
}

/** No response bodies or submitted passwords may escape into errors or logs. No retries. */
export async function replaceRecoveredPassword(subject: string, password: string): Promise<string> {
  const config = recoveryKeycloakConfig();
  try {
    const tokenResponse = await fetch(`${config.issuer}/protocol/openid-connect/token`, {
      method: 'POST', cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(10_000),
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'client_credentials', client_id: config.clientId, client_secret: config.secret }),
    });
    if (!tokenResponse.ok) throw new Error();
    const token = await tokenResponse.json() as { access_token?: unknown };
    if (typeof token.access_token !== 'string' || !token.access_token) throw new Error();
    const headers = { Authorization: `Bearer ${token.access_token}`, 'Content-Type': 'application/json' };
    const userUrl = `${config.admin}/users/${encodeURIComponent(subject)}`;
    const userResponse = await fetch(userUrl, { headers, cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(10_000) });
    if (!userResponse.ok) throw new Error();
    const user = await userResponse.json() as { enabled?: boolean; username?: unknown };
    if (user.enabled !== true || typeof user.username !== 'string' || !user.username) throw new Error();
    const response = await fetch(`${userUrl}/reset-password`, {
      method: 'PUT', headers, cache: 'no-store', redirect: 'error', signal: AbortSignal.timeout(10_000),
      body: JSON.stringify({ type: 'password', value: password, temporary: false }),
    });
    if (response.status !== 204) throw new Error();
    return user.username;
  } catch {
    throw new Error('The password change could not be confirmed. Try logging in with your new password; otherwise start recovery again. Choose a password that meets the account password policy.');
  }
}
