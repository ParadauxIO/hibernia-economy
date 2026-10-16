import NextAuth from 'next-auth';
import Keycloak from 'next-auth/providers/keycloak';
import { withoutUndefined } from '@/lib/util/withoutUndefined';

/**
 * Auth.js v5 + Keycloak.
 *
 * Notes for the `treasury-explorer` realm/client:
 * - Public client (PKCE), no secret. We still set `clientSecret: ''` because the
 *   provider field is required; `client.token_endpoint_auth_method = 'none'`
 *   forces the public-client flow on the wire.
 * - The `minecraft_uuid` / `minecraft_name` user attributes are exposed on the ID
 *   token via a protocol mapper; we lift them onto the stateless session JWT.
 * - We deliberately DO NOT store the Keycloak access/refresh tokens in the session.
 *   The explorer never calls Keycloak APIs (it reads the economy DB directly), and
 *   stashing two full JWTs bloated the session cookie past the ~4 KB browser limit,
 *   so Auth.js chunked it — and mobile browsers / Safari (ITP, stricter cookie caps)
 *   drop or truncate the chunks. That left users effectively logged out on some
 *   devices and unable to see their own data while it worked on others. Keeping the
 *   cookie tiny (sub + minecraft claim only) makes the session reliable everywhere;
 *   session lifetime is governed by Auth.js's own JWT maxAge.
 */
export const { auth, handlers, signIn, signOut } = NextAuth({
  providers: [
    Keycloak({
      ...withoutUndefined({ issuer: process.env.AUTH_KEYCLOAK_ISSUER, clientId: process.env.AUTH_KEYCLOAK_ID }),
      clientSecret: process.env.AUTH_KEYCLOAK_SECRET ?? '',
      client: { token_endpoint_auth_method: 'none' },
    }),
  ],
  session: { strategy: 'jwt' },
  callbacks: {
    async jwt({ token, account, profile }) {
      // First sign-in carries the ID-token profile: lift the minecraft claim onto
      // the session JWT. On later requests account/profile are absent and the value
      // already on the token is preserved.
      if (account && profile) {
        const p = profile as { minecraft_uuid?: unknown; minecraft_name?: unknown };
        if (typeof p.minecraft_uuid === 'string') token.minecraftUuid = p.minecraft_uuid;
        if (typeof p.minecraft_name === 'string') token.minecraftName = p.minecraft_name;
      }
      return token;
    },
    async session({ session, token }) {
      session.keycloakSub = typeof token.sub === 'string' ? token.sub : undefined;
      session.minecraftUuid = typeof token.minecraftUuid === 'string' ? token.minecraftUuid : undefined;
      session.minecraftName = typeof token.minecraftName === 'string' ? token.minecraftName : undefined;
      return session;
    },
  },
});
