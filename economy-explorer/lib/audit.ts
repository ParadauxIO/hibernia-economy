import 'server-only';
import { headers } from 'next/headers';
import { sql } from 'kysely';
import { db, uuidToBin } from '@/lib/db';
import type { Viewer } from '@/lib/auth/viewer';

/**
 * Per-page audit insert for privacy-sensitive accesses (admin / government
 * inspecting another player's data). Mirrors Spring's ExplorerAuditInterceptor
 * but called explicitly from the RSC page once it has decided to render gated
 * content — middleware can't know which tier the route required.
 *
 * Fail-open: a thrown error from this helper never blocks the page.
 */
export async function audit(args: {
  viewer: Extract<Viewer, { anon: false }>;
  method: string;
  path: string;
  targetType: 'account' | 'transaction' | 'firm' | 'player' | 'chestshop' | 'global';
  targetId: string | null;
  outcome: number;
  sourceIp: string | null;
}): Promise<void> {
  try {
    await sql`
      INSERT INTO explorer_audit
        (actor_sub, actor_uuid_bin, actor_name, actor_role,
         method, path, target_type, target_id, outcome, source_ip)
      VALUES
        (${args.viewer.keycloakSub},
         ${args.viewer.minecraftUuid ? uuidToBin(args.viewer.minecraftUuid) : null},
         ${args.viewer.minecraftName},
         ${args.viewer.role},
         ${args.method}, ${args.path}, ${args.targetType}, ${args.targetId},
         ${args.outcome}, ${args.sourceIp})
    `.execute(db);
  } catch (err) {
    // Audit is fail-open — log but do not interrupt the request.
    console.warn('[audit] insert failed:', err);
  }
}

/**
 * Convenience wrapper for RSC pages: resolves the client IP from request
 * headers and records a privileged view. No-op for anonymous viewers (only
 * authenticated, privilege-gated accesses are auditable). Fail-open.
 */
export async function auditView(
  viewer: Viewer,
  opts: {
    path: string;
    targetType: 'account' | 'transaction' | 'firm' | 'player' | 'chestshop' | 'global';
    targetId?: string | null;
    method?: string;
    outcome?: number;
  },
): Promise<void> {
  if (viewer.anon) return;
  let sourceIp: string | null = null;
  try {
    const h = await headers();
    // Prefer the trusted gateway's address over the client-controlled
    // X-Forwarded-For leftmost hop, which is spoofable — a caller could otherwise
    // forge the source IP recorded against their own audited actions (same reasoning
    // as the rate-limiter, ADT-15/124). x-envoy-external-address is set by the Envoy
    // gateway and x-real-ip by the ingress; the XFF leftmost hop is a last-resort,
    // best-effort fallback only.
    sourceIp =
      h.get('x-envoy-external-address')?.trim() ||
      h.get('x-real-ip')?.trim() ||
      h.get('x-forwarded-for')?.split(',')[0]?.trim() ||
      null;
  } catch {
    /* headers() unavailable — leave IP null */
  }
  await audit({
    viewer,
    method: opts.method ?? 'GET',
    path: opts.path,
    targetType: opts.targetType,
    targetId: opts.targetId ?? null,
    outcome: opts.outcome ?? 200,
    sourceIp,
  });
}
