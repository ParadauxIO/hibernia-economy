import { Counter, Gauge, Histogram, type Registry } from 'prom-client';
import { metrics } from '@/lib/metrics';

// Rollup worker metrics (PAR-532), registered on the explorer's registry
// (lib/metrics.ts, served at /metrics). Keyed by registry so every bundle's copy
// of this module, and a test that resets the registry, share one set.
//
// Alert (spec §4.2): newest successful step older than 10 minutes, e.g.
//   time() - max(explorer_rollup_last_success_timestamp_seconds) > 600

const STEP_BUCKETS = [0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 30, 60, 120, 300];
const KEY = Symbol.for('economy-explorer.rollup.metrics');

function create(registry: Registry) {
  return {
    stepSeconds: new Histogram({
      name: 'explorer_rollup_step_seconds',
      help: 'Time to rebuild one rollup job for one UTC day, by job and pass (fresh, nightly, backfill).',
      labelNames: ['job', 'pass'] as const,
      buckets: STEP_BUCKETS,
      registers: [registry],
    }),
    steps: new Counter({
      name: 'explorer_rollup_steps_total',
      help: 'Rollup job-day steps by job, pass and outcome (ok, error).',
      labelNames: ['job', 'pass', 'outcome'] as const,
      registers: [registry],
    }),
    lastSuccess: new Gauge({
      name: 'explorer_rollup_last_success_timestamp_seconds',
      help: 'Unix time of the last successful step per job, on the leader pod.',
      labelNames: ['job'] as const,
      registers: [registry],
    }),
    leader: new Gauge({
      name: 'explorer_rollup_leader',
      help: '1 while this pod holds the rollup leader lease, else 0.',
      registers: [registry],
    }),
    backfillRemaining: new Gauge({
      name: 'explorer_rollup_backfill_days_remaining',
      help: 'Days of history a job still has to backfill (0 once complete).',
      labelNames: ['job'] as const,
      registers: [registry],
    }),
  };
}

type RollupMetrics = ReturnType<typeof create>;

export function rollupMetrics(): RollupMetrics {
  const { registry } = metrics();
  const store = globalThis as unknown as Record<symbol, WeakMap<Registry, RollupMetrics> | undefined>;
  const byRegistry = (store[KEY] ??= new WeakMap());
  let set = byRegistry.get(registry);
  if (!set) {
    set = create(registry);
    byRegistry.set(registry, set);
  }
  return set;
}
