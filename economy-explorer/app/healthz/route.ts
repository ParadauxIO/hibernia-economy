import { rollupWorkerState } from '@/lib/rollup/start';

// Lightweight liveness probe — returns 200 without touching the DB, so the
// container healthcheck doesn't run the heavy dashboard aggregates (which would
// turn DB slowness into a probe failure → pod restart, a cascading failure).
//
// `/healthz?rollup=1` adds the rollup worker's status as JSON (PAR-532): run-log
// staleness and per-job backfill coverage from the database, plus this pod's
// worker state. It is still always 200 — a disabled, lagging or unreachable
// worker is reported in the body, never as a probe failure.
export const dynamic = 'force-dynamic';

const HEADERS = { 'cache-control': 'no-store' };
const ROLLUP_READ_TIMEOUT_MS = 2000;

export async function GET(request: Request) {
  if (new URL(request.url).searchParams.get('rollup') !== '1') {
    return new Response('ok', { status: 200, headers: { ...HEADERS, 'content-type': 'text/plain' } });
  }
  const worker = rollupWorkerState();
  let rollup: unknown;
  try {
    const { rollupHealth } = await import('@/lib/services/rollup');
    rollup = await Promise.race([
      rollupHealth(),
      new Promise((_, reject) => setTimeout(() => reject(new Error('timed out')), ROLLUP_READ_TIMEOUT_MS).unref()),
    ]);
  } catch (e) {
    // The database's own error text stays in the log, not the public body.
    console.warn('[healthz] rollup status unavailable', e);
    rollup = { unavailable: true };
  }
  return Response.json({ status: 'ok', worker, rollup }, { status: 200, headers: HEADERS });
}
