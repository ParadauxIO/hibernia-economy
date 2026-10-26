import type { Instrumentation } from 'next';

// Next.js calls register() once per server process before serving requests
// (PAR-531). Everything here is Node-only: the edge runtime has no HTTP server
// to wrap and no place for a background worker.
export async function register(): Promise<void> {
  if (process.env.NEXT_RUNTIME !== 'nodejs') return;
  const { metrics } = await import('./lib/metrics');
  const { installRequestTiming } = await import('./lib/requestTiming');
  metrics(); // create the registry now so process metrics start at boot
  installRequestTiming();
  // The rollup worker (PAR-532) owns lib/rollup/; this call is its only hook.
  const { startRollupWorker } = await import('./lib/rollup/start');
  startRollupWorker();
}

export const onRequestError: Instrumentation.onRequestError = async (_error, _request, context) => {
  if (process.env.NEXT_RUNTIME !== 'nodejs') return;
  const { recordRequestError } = await import('./lib/metrics');
  recordRequestError(context.routePath, `${context.routeType}:${context.renderSource ?? 'none'}`);
};
