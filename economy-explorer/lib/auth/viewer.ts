import 'server-only';
import { cache } from 'react';
import { cookies } from 'next/headers';
import { auth } from '@/lib/auth/authjs';
import { resolveAuthorization, permissionsForRoles } from '@/lib/sql/role';
import { findIdentityBySub, upsertIdentity } from '@/lib/sql/identity';
import { roleFromCapabilities, normalizeCapability } from '@/lib/auth/capabilities';
import type { Permission } from './policy';

export type Viewer =
  | {
      anon: true;
      role: null;
      capabilities: readonly string[];
      permissions: readonly Permission[];
      keycloakSub: null;
      minecraftUuid: null;
      minecraftName: null;
      linked: false;
    }
  | {
      anon: false;
      keycloakSub: string;
      minecraftUuid: string | null;
      minecraftName: string | null;
      linked: boolean;
      role: string;
      capabilities: readonly string[];
      permissions: readonly Permission[];
    };

/**
 * The `player` role as seeded by V31. Used only when the database cannot tell us
 * the live baseline, so a signed-in player keeps their ordinary reads during a
 * DB blip instead of dropping below the anonymous-plus-login baseline. Keep in
 * step with the V31 seed; admins may narrow the live baseline, which is why the
 * error path asks the database first.
 */
export const SEEDED_PLAYER_PERMISSIONS: readonly Permission[] = [
  'player.read',
  'account.read',
  'firm.read',
  'firm.financials.read',
  'webhooks.read',
  'webhooks.manage',
];

/** The live player baseline when the role tables answer, else the V31 seed. Never anything elevated. */
async function playerBaseline(): Promise<readonly Permission[]> {
  try {
    return await permissionsForRoles(['player']);
  } catch {
    return SEEDED_PLAYER_PERMISSIONS;
  }
}

const ANON: Viewer = {
  anon: true,
  role: null,
  capabilities: [],
  permissions: [],
  keycloakSub: null,
  minecraftUuid: null,
  minecraftName: null,
  linked: false,
};

/**
 * Per-request viewer resolution. Deduped within one RSC render via React `cache()`.
 *
 * Sources:
 * - Auth.js session → keycloak_sub + (optional) minecraft_uuid claim.
 * - explorer_identity → durable sub→player link (auto-upserted from the claim).
 * - explorer_role     → direct role grants. Absent = 'player'.
 *
 * Anonymous is the public baseline (no session, no role).
 */
// E2E test-auth shim. STRICTLY gated on E2E_TEST_AUTH=1, which is set ONLY in
// the E2E test environment (never in prod images). Lets Playwright assume a
// role via the `e2e_viewer` cookie without standing up Keycloak in CI — the
// only mocked seam; the DB, rendering and all gating logic run for real.
// 'alice' uses the seeded player UUID who owns account #1 (own-data tests).
const E2E_ALICE_UUID = '00000000-0000-0000-0000-00000000a1ce';
async function e2eViewer(): Promise<Viewer | null> {
  const role = (await cookies()).get('e2e_viewer')?.value;
  if (!role || role === 'anon') return null;
  if (normalizeCapability(role)) {
    return {
      anon: false,
      keycloakSub: `e2e-${role}`,
      minecraftUuid: E2E_ALICE_UUID,
      minecraftName: 'Alice',
      linked: true,
      role,
      capabilities: [...new Set(['player', role])],
      permissions: await permissionsForRoles(['player', role]),
    };
  }
  return null;
}

export const getViewer = cache(async (): Promise<Viewer> => {
  if (process.env.E2E_TEST_AUTH === '1') {
    const shim = await e2eViewer();
    if (shim) return shim;
  }

  const session = await auth();
  const sub = session?.keycloakSub;
  if (!session || !sub) return ANON;

  const claimUuid = session.minecraftUuid;
  const claimName = session.minecraftName;

  // Identity/role resolution touches the DB. Fail SOFT: a DB blip degrades the
  // viewer to an unlinked 'player' (so authenticated browsing of public pages
  // keeps working) rather than throwing and 500-ing the whole layout. Elevated
  // roles are never granted on the error path.
  try {
    // Resolve the durable identity link (keyed on keycloak_sub). The plugin
    // ordinarily writes this row on `/treasuryapi ui link <code>` redemption; if
    // the session carries a minecraft_uuid claim we keep the row current.
    //
    // Read first, write only on drift. The old code upserted on *every*
    // authenticated request — a needless write round-trip to the remote DB on the
    // universal layout path (getViewer runs for every page). Now the common case
    // (row exists and matches the claim) is a single read; we only write when the
    // link is missing or the claim's uuid/name has changed.
    let identity = await findIdentityBySub(sub);
    if (claimUuid) {
      const drifted =
        identity != null &&
        (identity.playerUuid !== claimUuid || (claimName ?? null) !== (identity.minecraftName ?? null));
      if (!identity || drifted) {
        await upsertIdentity({
          sub,
          playerUuid: claimUuid,
          minecraftName: claimName ?? null,
          linkedBy: 'token-claim',
        });
        identity = await findIdentityBySub(sub);
      }
    }

    const playerUuid = identity?.playerUuid ?? claimUuid ?? null;
    const linked = !!identity;

    const { roles: capabilities, permissions } = await resolveAuthorization(playerUuid);

    return {
      anon: false,
      keycloakSub: sub,
      minecraftUuid: playerUuid,
      minecraftName: identity?.minecraftName ?? claimName ?? null,
      linked,
      role: roleFromCapabilities(capabilities),
      capabilities,
      permissions,
    };
  } catch (err) {
    console.warn('[viewer] identity/role resolution failed — degrading to player:', err);
    return {
      anon: false,
      keycloakSub: sub,
      minecraftUuid: claimUuid ?? null,
      minecraftName: claimName ?? null,
      linked: false,
      role: 'player',
      capabilities: ['player'],
      permissions: await playerBaseline(),
    };
  }
});
