import 'server-only';
import { headers } from 'next/headers';
import { sql } from 'kysely';
import { db, uuidToBin } from '@/lib/db';
import type { Viewer } from '@/lib/auth/viewer';
import { auditRole } from '@/lib/auth/access';
import { hasPermission } from '@/lib/auth/policy';
import { recordAuditWrite, type AuditWriteKind } from '@/lib/metrics';
import { redis, tenantKey } from '@/lib/redis';
import { serializeAuditValue } from '@/lib/auditDiff';
import {
  RELATIONSHIP_READ_WINDOW_MS,
  memoryThrottleStore,
  redisThrottleStore,
  relationshipReadKey,
  type ThrottleStore,
} from '@/lib/auditThrottle';

// The explorer's audit trail (explorer_audit, V3 + V45). Three kinds of row:
//   * views: privileged reads of other players' data (auditView), action 'view';
//   * mutations: admin changes with the values before and after (auditMutation);
//   * relationship reads: GOVERNMENT and BUSINESS accounts read through an
//     account or firm relationship rather than a global `.all` permission
//     (auditRelationshipRead), throttled to one row per viewer, account and hour.
// Every write is fail-open: an audit failure is logged and counted in
// explorer_audit_writes_total{outcome="error"}, and never fails the request.

export type AuditTargetType =
  | 'account' | 'transaction' | 'firm' | 'player' | 'chestshop' | 'global'
  | 'group' | 'role' | 'api_key' | 'webhook';

type SignedIn = Extract<Viewer, { anon: false }>;

interface AuditRow {
  viewer: SignedIn;
  method: string;
  path: string;
  action: string;
  targetType: AuditTargetType;
  targetId: string | null;
  outcome: number;
  sourceIp: string | null;
  before?: unknown;
  after?: unknown;
}

const MAX_TARGET_ID = 64;
const MAX_PATH = 255;
const MAX_ACTION = 48;

function rowValues(r: AuditRow) {
  return sql`(${r.viewer.keycloakSub},
    ${r.viewer.minecraftUuid ? uuidToBin(r.viewer.minecraftUuid) : null},
    ${r.viewer.minecraftName},
    ${auditRole(r.viewer)},
    ${r.action.slice(0, MAX_ACTION)},
    ${r.method}, ${r.path.slice(0, MAX_PATH)}, ${r.targetType}, ${r.targetId?.slice(0, MAX_TARGET_ID) ?? null},
    ${r.outcome}, ${r.sourceIp},
    ${serializeAuditValue(r.before)}, ${serializeAuditValue(r.after)})`;
}

async function insertRows(kind: AuditWriteKind, rows: readonly AuditRow[]): Promise<void> {
  if (rows.length === 0) return;
  try {
    await sql`
      INSERT INTO explorer_audit
        (actor_sub, actor_uuid_bin, actor_name, actor_role, action,
         method, path, target_type, target_id, outcome, source_ip, before_json, after_json)
      VALUES ${sql.join(rows.map(rowValues))}
    `.execute(db);
    recordAuditWrite(kind, 'ok');
  } catch (err) {
    // Fail-open: log and count, never interrupt the request.
    recordAuditWrite(kind, 'error');
    console.warn('[audit] insert failed:', err);
  }
}

/**
 * Low-level insert for one audit row. Prefer {@link auditView},
 * {@link auditMutation} or {@link auditRelationshipRead}. Fail-open.
 */
export async function audit(args: {
  viewer: SignedIn;
  method: string;
  path: string;
  targetType: AuditTargetType;
  targetId: string | null;
  outcome: number;
  sourceIp: string | null;
  action?: string;
  before?: unknown;
  after?: unknown;
}): Promise<void> {
  const kind: AuditWriteKind = args.before !== undefined || args.after !== undefined || args.method !== 'GET' ? 'mutation' : 'view';
  await insertRows(kind, [{ ...args, action: args.action ?? (args.method === 'GET' ? 'view' : 'other') }]);
}

/** The caller's address from the trusted gateway headers, or null. */
async function sourceIp(): Promise<string | null> {
  try {
    const h = await headers();
    // Prefer the trusted gateway's address over the client-controlled
    // X-Forwarded-For leftmost hop, which is spoofable — a caller could otherwise
    // forge the source IP recorded against their own audited actions (same reasoning
    // as the rate-limiter, ADT-15/124). x-envoy-external-address is set by the Envoy
    // gateway and x-real-ip by the ingress; the XFF leftmost hop is a last-resort,
    // best-effort fallback only.
    return (
      h.get('x-envoy-external-address')?.trim() ||
      h.get('x-real-ip')?.trim() ||
      h.get('x-forwarded-for')?.split(',')[0]?.trim() ||
      null
    );
  } catch {
    return null; // headers() unavailable outside a request
  }
}

/**
 * Records a privileged view from an RSC page or route. No-op for anonymous
 * viewers (only authenticated, privilege-gated accesses are auditable). Fail-open.
 */
export async function auditView(
  viewer: Viewer,
  opts: {
    path: string;
    targetType: AuditTargetType;
    targetId?: string | null;
    method?: string;
    outcome?: number;
    /** Defaults to 'view' for GET and 'other' otherwise. Mutations should use auditMutation. */
    action?: string;
  },
): Promise<void> {
  if (viewer.anon) return;
  const method = opts.method ?? 'GET';
  await audit({
    viewer,
    method,
    path: opts.path,
    action: opts.action ?? (method === 'GET' ? 'view' : 'other'),
    targetType: opts.targetType,
    targetId: opts.targetId ?? null,
    outcome: opts.outcome ?? 200,
    sourceIp: await sourceIp(),
  });
}

/**
 * Records one admin change with what it changed. Call it after the change
 * succeeded, with the value as it was read just before (`before`) and as the
 * write returned or left it (`after`). Either may be null: a create has no
 * before, a delete no after. Values are stored as JSON, capped at 16 KiB each.
 * Fail-open.
 */
export async function auditMutation(
  viewer: Viewer,
  opts: {
    /** Stable verb, e.g. 'account.rename', 'group.roles.update'. Shown and filtered on /admin/audit. */
    action: string;
    /** The tool that made the change, e.g. '/admin/accounts/rename'. */
    path: string;
    targetType: AuditTargetType;
    targetId: string | number | null;
    before: unknown;
    after: unknown;
    outcome?: number;
  },
): Promise<void> {
  if (viewer.anon) return;
  await insertRows('mutation', [{
    viewer,
    method: 'POST',
    path: opts.path,
    action: opts.action,
    targetType: opts.targetType,
    targetId: opts.targetId === null ? null : String(opts.targetId),
    outcome: opts.outcome ?? 200,
    sourceIp: await sourceIp(),
    before: opts.before ?? null,
    after: opts.after ?? null,
  }]);
}

/** An account read through a relationship. */
export interface RelationshipReadTarget {
  accountId: number;
  /** The account's AccountType. Only GOVERNMENT and BUSINESS reads are recorded. */
  accountType: string;
}

const fallbackThrottle = memoryThrottleStore();
let throttle: ThrottleStore = redisThrottleStore(() => redis(), fallbackThrottle, tenantKey);

/** Test hook: swap the throttle store (unit tests use memoryThrottleStore). */
export function setRelationshipReadThrottleForTests(store: ThrottleStore | null): void {
  throttle = store ?? redisThrottleStore(() => redis(), fallbackThrottle, tenantKey);
}

/** True when a global permission, not a relationship, already lets the viewer read this account type. */
export function readCoveredByGlobalPermission(viewer: Viewer, accountType: string): boolean {
  if (hasPermission(viewer, 'account.read.all')) return true;
  if (accountType === 'GOVERNMENT') return hasPermission(viewer, 'government.read.all');
  if (accountType === 'BUSINESS') return hasPermission(viewer, 'firm.financials.read.all');
  return false;
}

/**
 * Records that `viewer` read private data of a GOVERNMENT or BUSINESS account
 * through a relationship: ownership, direct account access, an LP group grant,
 * or firm finance access (spec §1.6, §10.1, §12).
 *
 * Call it after your access check has passed and you are about to render the
 * account's private data (history, totals, reports). Pass one target or every
 * account a page aggregates; one INSERT covers them all.
 *
 * It decides on its own what to record, so callers don't need to filter:
 *   * anonymous viewers, PERSONAL and SYSTEM accounts are skipped;
 *   * reads covered by a global permission are skipped (`account.read.all`,
 *     plus `government.read.all` for GOVERNMENT and `firm.financials.read.all`
 *     for BUSINESS): those are not relationship reads;
 *   * each viewer/account pair is recorded at most once per clock hour, shared
 *     across pods through Redis (per pod if Redis is unavailable).
 *
 * Rows have action 'relationship.read', target_type 'account', and target_id
 * the account id. Fail-open: it never throws and never blocks the page for
 * longer than one INSERT.
 *
 * @param path the route that read it, e.g. `/gov/accounts` or `/accounts/[id]`.
 *
 * @example
 *   if (await canViewAccount(id, viewer)) {
 *     await auditRelationshipRead(viewer, { accountId: id, accountType: account.account_type }, `/accounts/${id}`);
 *   }
 */
export async function auditRelationshipRead(
  viewer: Viewer,
  target: RelationshipReadTarget | readonly RelationshipReadTarget[],
  path = '/accounts/[id]',
): Promise<void> {
  if (viewer.anon) return;
  const targets = (Array.isArray(target) ? target : [target]) as readonly RelationshipReadTarget[];
  const now = new Date();
  const seen = new Set<number>();
  const rows: AuditRow[] = [];
  let ip: string | null | undefined;
  try {
    for (const t of targets) {
      if (!Number.isSafeInteger(t.accountId) || seen.has(t.accountId)) continue;
      seen.add(t.accountId);
      if (t.accountType !== 'GOVERNMENT' && t.accountType !== 'BUSINESS') continue;
      if (readCoveredByGlobalPermission(viewer, t.accountType)) continue;
      const key = relationshipReadKey(viewer.keycloakSub, 'account', String(t.accountId), now);
      if (!await throttle.claim(key, RELATIONSHIP_READ_WINDOW_MS)) {
        recordAuditWrite('relationship', 'throttled');
        continue;
      }
      ip ??= await sourceIp();
      rows.push({
        viewer,
        method: 'GET',
        path,
        action: 'relationship.read',
        targetType: 'account',
        targetId: String(t.accountId),
        outcome: 200,
        sourceIp: ip,
      });
    }
  } catch (err) {
    recordAuditWrite('relationship', 'error');
    console.warn('[audit] relationship read throttle failed:', err);
    return;
  }
  await insertRows('relationship', rows);
}
