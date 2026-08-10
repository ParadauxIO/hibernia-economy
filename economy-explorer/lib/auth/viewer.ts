import 'server-only';
import { cache } from 'react';
import { cookies } from 'next/headers';
import { auth } from '@/lib/auth/authjs';
import { findRoles, type ExplorerRole } from '@/lib/sql/role';
import { findIdentityBySub, upsertIdentity } from '@/lib/sql/identity';
import { findCapabilities } from '@/lib/sql/group';
import {
  legacyRoleCapabilities,
  roleFromCapabilities,
  normalizeCapability,
  type Capability,
} from '@/lib/auth/capabilities';

export type Viewer =
  | {
      anon: true;
      role: null;
      capabilities: readonly Capability[];
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
      role: ExplorerRole | 'player';
      capabilities: readonly Capability[];
    };

const ANON: Viewer = {
  anon: true,
  role: null,
  capabilities: [],
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
 * - explorer_role     → elevated grants (admin / government). Absent = 'player'.
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
  if (role === 'player' || role === 'admin' || role === 'government') {
    return {
      anon: false,
      keycloakSub: `e2e-${role}`,
      minecraftUuid: E2E_ALICE_UUID,
      minecraftName: 'Alice',
      linked: true,
      role,
      capabilities: legacyRoleCapabilities(role),
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

    // Effective capabilities = group grants ∪ legacy explorer_role grants
    // (mapped to capabilities). role is then derived from the capability set so
    // existing viewer.role / requireRole checks keep working — a group granting
    // the 'admin' capability now also yields role 'admin'.
    const caps = new Set<Capability>();
    if (playerUuid) {
      const [elevated, groupCaps] = await Promise.all([
        findRoles(playerUuid),
        findCapabilities(playerUuid),
      ]);
      for (const r of elevated) for (const c of legacyRoleCapabilities(r)) caps.add(c);
      // Group rows store raw capability strings; normalize so legacy 'staff.audit'
      // grants resolve to the current 'viewer' capability (and unknowns drop out).
      for (const c of groupCaps) {
        const norm = normalizeCapability(c);
        if (norm) caps.add(norm);
      }
    }
    const capabilities = [...caps];

    return {
      anon: false,
      keycloakSub: sub,
      minecraftUuid: playerUuid,
      minecraftName: identity?.minecraftName ?? claimName ?? null,
      linked,
      role: roleFromCapabilities(capabilities),
      capabilities,
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
      capabilities: [],
    };
  }
});
