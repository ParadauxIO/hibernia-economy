// Entry point called from instrumentation.ts register() (owned by PAR-531).
// Starts the rollup worker in this Node process; one pod leads at a time through
// the Redis lease. The worker modules are imported lazily, so instrumentation
// stays light and nothing database-related loads when the worker is off.

type Env = Record<string, string | undefined>;

/**
 * Whether this process should run the worker. ROLLUP_WORKER=off always
 * disables it and =on always enables it. Otherwise it runs, except under
 * Vitest, the e2e server (E2E_TEST_AUTH=1), `next build` and the edge runtime,
 * where a test must opt in with ROLLUP_WORKER=on.
 */
export function rollupWorkerEnabled(env: Env): boolean {
  if (env.NEXT_RUNTIME === 'edge' || env.NEXT_PHASE === 'phase-production-build') return false;
  const setting = env.ROLLUP_WORKER?.trim().toLowerCase();
  if (setting === 'off') return false;
  if (setting === 'on') return true;
  return !env.VITEST && env.E2E_TEST_AUTH !== '1';
}

// Next.js loads this module into several bundles (instrumentation, route
// handlers), each with its own module scope, so the state that /healthz reads
// lives on globalThis like lib/metrics.ts's registry.
interface WorkerState { started: boolean; leader: () => boolean }
const STATE_KEY = Symbol.for('economy-explorer.rollup.worker');
const store = globalThis as unknown as Record<symbol, WorkerState | undefined>;
const state = (): WorkerState => (store[STATE_KEY] ??= { started: false, leader: () => false });

/** This process's view of the worker, for /healthz. */
export function rollupWorkerState(): { enabled: boolean; started: boolean; leader: boolean } {
  return { enabled: rollupWorkerEnabled(process.env), started: state().started, leader: state().leader() };
}

export function startRollupWorker(): void {
  if (state().started || !rollupWorkerEnabled(process.env)) return;
  state().started = true;
  void (async () => {
    const [{ hostname }, { randomUUID }, { redis, tenantKey }, { rollupDb }, { LeaderLease }, { RollupWorker }] =
      await Promise.all([
        import('node:os'), import('node:crypto'), import('@/lib/redis'), import('./db'), import('./lease'),
        import('./worker'),
      ]);
    const lease = new LeaderLease(redis(), tenantKey('rollup:leader'), `${hostname()}:${process.pid}:${randomUUID()}`);
    const worker = new RollupWorker({ db: rollupDb(), lease });
    state().leader = () => lease.held;
    // Release the lease on shutdown so a successor need not wait for the TTL.
    // If nothing else handles the signal, re-raise it for the default exit.
    const onSignal = (signal: NodeJS.Signals) => {
      void worker.stop().finally(() => {
        if (process.listenerCount(signal) === 0) process.kill(process.pid, signal);
      });
    };
    process.once('SIGTERM', onSignal);
    process.once('SIGINT', onSignal);
    worker.start();
  })().catch((e: unknown) => {
    state().started = false;
    console.warn('[rollup] worker failed to start', e);
  });
}
