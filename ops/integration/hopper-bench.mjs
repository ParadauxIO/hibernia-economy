#!/usr/bin/env node
// PAR-523 hopper benchmark: runs the hopper-bench profile of run.sh across variants,
// scenarios and repetitions, then summarises the per-run JSON written by DcDiag.
//
//   node ops/integration/hopper-bench.mjs [--reps 3] [--scenarios typical,overload]
//       [--variants event-off,no-listener,legacy,worker,worker-monitor-only] [--warmup 90] [--sample 300]
//       [--out build/hopper-bench/<stamp>] [--skip-build] [--keep-jfr]
//   node ops/integration/hopper-bench.mjs --summarize <out dir>
//
// Variants (all with USE_STOCK_COUNTER true and production's Paper hopper settings):
//   event-off    Paper hopper.disable-move-event: true — no InventoryMoveItemEvent at all
//   no-listener  event on, ChestShop's move handlers unregistered (as ListenerControl can)
//   noop-listener  as no-listener plus one empty MONITOR handler: the cost of dispatch alone
//   legacy       the pre-6b35b153 listener (ops/integration/hopper-bench/legacy-listener.patch)
//   worker       this checkout: immediate protection + batched refresh worker
//   worker-monitor-only  the worker with ChestShop's HIGHEST (protection) handler removed:
//                production's ListenerControl config as of 2026-06, which removes that one only
// WorldGuard's move handler is unregistered in every variant, as production's ListenerControl does.
//
// Each run is a fresh disposable stack. Order is shuffled per repetition so drift on a
// shared machine does not favour one variant. Port and JDK settings come from the
// environment exactly as for run.sh (IT_MC_PORT, IT_DB_PORT, JAVA_HOME, ...).
import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const VARIANTS = ['event-off', 'no-listener', 'noop-listener', 'legacy', 'worker', 'worker-monitor-only'];
const REFRESH_INTERVAL_MS = 100 * 50;

// Fixed before any run (agreed 2026-11-05). Deltas are against event-off medians.
// Judged on main-thread CPU time per tick: wall-clock MSPT also counts time the server
// spends descheduled, which on a shared host swamped the effect being measured
// (2026-11-06: event-off MSPT +45% between days, its CPU per tick +3%).
const THRESHOLDS = {
  meanMsptIncreasePct: 2,
  p99IncreaseMs: 0.5,
  workerTickMaxMs: 2,
  freshnessP95Ms: REFRESH_INTERVAL_MS + 1000,
  backlogGrowthRatio: 1.1,
};

function parseArgs(argv) {
  const opts = { reps: 3, scenarios: ['typical', 'overload'], variants: VARIANTS, warmup: 90, sample: 300 };
  for (let i = 0; i < argv.length; i++) {
    const key = argv[i], value = argv[i + 1];
    switch (key) {
      case '--reps': opts.reps = Number(value); i++; break;
      case '--scenarios': opts.scenarios = value.split(','); i++; break;
      case '--variants': opts.variants = value.split(','); i++; break;
      case '--warmup': opts.warmup = Number(value); i++; break;
      case '--sample': opts.sample = Number(value); i++; break;
      case '--out': opts.out = resolve(value); i++; break;
      case '--skip-build': opts.skipBuild = true; break;
      case '--keep-jfr': opts.keepJfr = true; break;
      case '--summarize': opts.summarize = resolve(value); i++; break;
      default: throw new Error(`unknown argument ${key}`);
    }
  }
  for (const v of opts.variants) if (!VARIANTS.includes(v)) throw new Error(`unknown variant ${v}`);
  opts.out ??= join(ROOT, 'build/hopper-bench', new Date().toISOString().replace(/[:.]/g, '-'));
  return opts;
}

function run(cmd, args, options = {}) {
  const result = spawnSync(cmd, args, { stdio: 'inherit', ...options });
  if (result.status !== 0) throw new Error(`${cmd} ${args.join(' ')} exited ${result.status}`);
  return result;
}

function git(...args) {
  return spawnSync('git', args, { cwd: ROOT, encoding: 'utf8' }).stdout.trim();
}

/** The legacy jar is this checkout's HEAD with only the listener reverted. */
function buildLegacyJar(out) {
  if (git('status', '--porcelain', '--', 'chestshop')) {
    throw new Error('chestshop/ has uncommitted changes: the legacy jar is built from HEAD, so commit them first');
  }
  const src = join(out, 'legacy-src');
  rmSync(src, { recursive: true, force: true });
  run('git', ['worktree', 'add', '--detach', src, 'HEAD'], { cwd: ROOT });
  try {
    run('git', ['apply', join(ROOT, 'ops/integration/hopper-bench/legacy-listener.patch')], { cwd: src });
    run('sh', ['./gradlew', '--console=plain', '-q', ':chestshop:shadowJar', '-Pci=true'], { cwd: src });
    const libs = join(src, 'chestshop/build/libs');
    const jar = readdirSync(libs).find((f) => f.startsWith('chestshop-') && f.endsWith('.jar'));
    copyFileSync(join(libs, jar), join(out, 'legacy-chestshop.jar'));
  } finally {
    run('git', ['worktree', 'remove', '--force', src], { cwd: ROOT });
  }
  return join(out, 'legacy-chestshop.jar');
}

/** Deterministic Fisher–Yates so a rerun with the same reps gets the same order. */
function shuffled(items, seed) {
  const a = [...items];
  let s = seed * 2654435761 % 4294967296;
  for (let i = a.length - 1; i > 0; i--) {
    s = (s * 1664525 + 1013904223) % 4294967296;
    const j = s % (i + 1);
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

function runMatrix(opts) {
  const runs = join(opts.out, 'runs');
  mkdirSync(runs, { recursive: true });
  const legacyJar = opts.variants.includes('legacy') ? buildLegacyJar(opts.out) : null;
  // A resumed or extended run adds its own metadata instead of replacing the first.
  const metaFile = existsSync(join(opts.out, 'meta.json'))
    ? join(opts.out, `meta-${new Date().toISOString().replace(/[:.]/g, '-')}.json`) : join(opts.out, 'meta.json');
  writeFileSync(metaFile, JSON.stringify({
    commit: git('rev-parse', 'HEAD'),
    started: new Date().toISOString(),
    host: { cpus: (readFileSync('/proc/cpuinfo', 'utf8').match(/^processor/gm) || []).length,
            model: (readFileSync('/proc/cpuinfo', 'utf8').match(/model name\s*:\s*(.*)/) || [])[1] },
    opts: { ...opts, out: undefined },
    thresholds: THRESHOLDS,
  }, null, 2));
  let built = opts.skipBuild;
  for (let rep = 1; rep <= opts.reps; rep++) {
    for (const scenario of opts.scenarios) {
      for (const variant of shuffled(opts.variants, rep * 31 + scenario.length)) {
        const id = `${scenario}-${variant}-r${rep}`;
        const json = join(runs, `${id}.json`);
        if (existsSync(json)) { console.log(`skip ${id}: already recorded`); continue; }
        for (let attempt = 1; attempt <= 2; attempt++) {
          console.log(`\n### ${id} (attempt ${attempt})`);
          const result = spawnSync('bash', [join(ROOT, 'ops/integration/run.sh'), '--profile', 'hopper-bench'], {
            cwd: ROOT,
            stdio: ['ignore', 'pipe', 'pipe'],
            encoding: 'utf8',
            maxBuffer: 256 * 1024 * 1024,
            env: {
              ...process.env,
              IT_SKIP_BUILD: built ? '1' : '0',
              IT_WORK_DIR: join(opts.out, 'work'),
              IT_PAPER_HEAP: process.env.IT_PAPER_HEAP ?? '-Xms4G -Xmx4G',
              IT_BENCH_VARIANT: variant,
              IT_BENCH_SCENARIO: scenario,
              IT_BENCH_WARMUP_S: String(opts.warmup),
              IT_BENCH_SAMPLE_S: String(opts.sample),
              IT_BENCH_OUT: json,
              ...(legacyJar ? { IT_BENCH_CHESTSHOP_JAR: legacyJar } : {}),
            },
          });
          writeFileSync(join(runs, `${id}.log`), (result.stdout ?? '') + (result.stderr ?? ''));
          if (result.status !== 0) copyFileSync(join(runs, `${id}.log`), join(runs, `${id}.attempt${attempt}.log`));
          const serverLog = join(opts.out, 'work/server.log');
          if (existsSync(serverLog)) copyFileSync(serverLog, join(runs, `${id}.server.log`));
          const artifacts = join(opts.out, 'work/runtime-artifacts.json');
          if (existsSync(artifacts)) copyFileSync(artifacts, join(runs, `${id}.artifacts.json`));
          // Worker profiles are small (about 1.5 MB per 120 s) and explain each iteration's result.
          if (!opts.keepJfr && !variant.startsWith('worker')) rmSync(`${json}.jfr`, { force: true });
          if (result.status === 0 && existsSync(json)) { built = true; break; }
          console.error(`${id} failed (exit ${result.status}); see ${join(runs, `${id}.log`)}`);
        }
      }
    }
  }
}

// ── Summary ────────────────────────────────────────────────────────────────

const METRICS = {
  meanMspt: (r) => r.tickMs.mean,
  p95Mspt: (r) => r.tickMs.p95,
  meanTickCpu: (r) => r.tickCpuMs?.mean,
  p99TickCpu: (r) => r.tickCpuMs?.p99,
  workerDrainCpuP99Ms: (r) => r.workerDrainCpuMs?.p99,
  workerDrainCpuMaxMs: (r) => r.workerDrainCpuMs?.max,
  workerCpuTicksOver2ms: (r) => r.workerCpuTicksOver2ms,
  p99Mspt: (r) => r.tickMs.p99,
  maxMspt: (r) => r.tickMs.max,
  tps: (r) => r.tps,
  mainCpuMsPerTick: (r) => r.mainCpuMsPerTick,
  mainAllocMBPerSec: (r) => r.mainAllocMBPerSec,
  itemsPerSec: (r) => r.itemsDeliveredPerSec,
  workerSharePct: (r) => pct(r.profile.workerSamples, r.profile.mainSamples),
  listenerSharePct: (r) => pct(r.profile.listenerSamples, r.profile.mainSamples),
  chestShopSharePct: (r) =>
    pct(r.profile.workerSamples + r.profile.listenerSamples + r.profile.otherChestShopSamples, r.profile.mainSamples),
  moveEventSharePct: (r) => pct(r.profile.moveEventConstructionSamples, r.profile.mainSamples),
  workerDrainP99Ms: (r) => r.workerDrainMs?.p99,
  workerDrainMaxMs: (r) => r.workerDrainMs?.max,
  workerTicksOver2ms: (r) => r.workerTicksOver2ms,
  probeInP95Ms: (r) => r.probeInMs.p95,
  probeOutP95Ms: (r) => r.probeOutMs.p95,
  probeMisses: (r) => r.probeInMisses + r.probeOutMisses,
  probeSamples: (r) => r.probeInMs.n + r.probeOutMs.n,
  pendingMax: (r) => r.backlog.pending.max,
  reconciliationMax: (r) => r.backlog.reconciliation.max,
  gcPauseMs: (r) => r.profile.gcPauseMs,
};

const pct = (a, b) => (b ? (100 * a) / b : 0);

function stats(values) {
  const v = values.filter((x) => typeof x === 'number' && Number.isFinite(x)).sort((a, b) => a - b);
  if (!v.length) return null;
  const mean = v.reduce((s, x) => s + x, 0) / v.length;
  const sd = Math.sqrt(v.reduce((s, x) => s + (x - mean) ** 2, 0) / Math.max(1, v.length - 1));
  const mid = Math.floor(v.length / 2);
  return { n: v.length, median: v.length % 2 ? v[mid] : (v[mid - 1] + v[mid]) / 2, min: v[0], max: v.at(-1),
           cvPct: mean ? (100 * sd) / mean : 0 };
}

function backlogGrows(r) {
  return ['pending', 'reconciliation', 'deferredPositions', 'deferredChunks'].some((k) => {
    const b = r.backlog[k];
    return b.finalThirdMean > THRESHOLDS.backlogGrowthRatio * b.middleThirdMean + 16;
  });
}

function judge(group, baseline) {
  if (!baseline) return null;
  const m = (g, k) => g.metrics[k]?.median;
  const checks = {
    meanTickCpu: { value: pct(m(group, 'meanTickCpu') - m(baseline, 'meanTickCpu'), m(baseline, 'meanTickCpu')),
                   limit: THRESHOLDS.meanMsptIncreasePct, unit: '% CPU/tick vs event-off' },
    p99TickCpu: { value: m(group, 'p99TickCpu') - m(baseline, 'p99TickCpu'), limit: THRESHOLDS.p99IncreaseMs,
                  unit: 'ms CPU/tick vs event-off' },
  };
  if (group.variant.startsWith('worker')) {
    checks.workerTickMax = { value: group.metrics.workerDrainCpuMaxMs?.max, limit: THRESHOLDS.workerTickMaxMs,
                             unit: 'ms drain CPU, worst run' };
    checks.freshnessIn = { value: group.metrics.probeInP95Ms?.max, limit: THRESHOLDS.freshnessP95Ms, unit: 'ms p95, worst run' };
    checks.freshnessOut = { value: group.metrics.probeOutP95Ms?.max, limit: THRESHOLDS.freshnessP95Ms, unit: 'ms p95, worst run' };
    checks.probeMisses = { value: group.metrics.probeMisses?.max, limit: 0, unit: 'timeouts, worst run' };
    checks.backlogLevels = { value: group.backlogGrowingRuns, limit: 0, unit: 'runs with growing backlog' };
  }
  for (const c of Object.values(checks)) c.pass = typeof c.value === 'number' && c.value <= c.limit;
  return checks;
}

function summarize(out) {
  const runsDir = join(out, 'runs');
  const results = readdirSync(runsDir).filter((f) => f.endsWith('.json') && !f.endsWith('.artifacts.json'))
    .map((f) => JSON.parse(readFileSync(join(runsDir, f), 'utf8')));
  const groups = {};
  for (const r of results) (groups[`${r.scenario}/${r.variant}`] ??= { scenario: r.scenario, variant: r.variant, runs: [] }).runs.push(r);
  for (const g of Object.values(groups)) {
    g.metrics = Object.fromEntries(Object.entries(METRICS).map(([k, f]) => [k, stats(g.runs.map(f))]));
    g.backlogGrowingRuns = g.runs.filter(backlogGrows).length;
    g.fixture = g.runs[0].fixture;
    g.handlers = g.runs[0].handlers;
  }
  for (const g of Object.values(groups)) g.checks = judge(g, groups[`${g.scenario}/event-off`]);
  const sample = results[0];
  const summary = {
    meta: existsSync(join(out, 'meta.json')) ? JSON.parse(readFileSync(join(out, 'meta.json'), 'utf8')) : {},
    environment: sample && { serverVersion: sample.serverVersion, javaVersion: sample.javaVersion,
                             hopperSettings: sample.hopperSettings },
    thresholds: THRESHOLDS,
    groups: Object.values(groups).sort((a, b) => a.scenario.localeCompare(b.scenario)
      || VARIANTS.indexOf(a.variant) - VARIANTS.indexOf(b.variant)),
  };
  writeFileSync(join(out, 'summary.json'), JSON.stringify(summary, null, 2));
  writeFileSync(join(out, 'summary.md'), markdown(summary));
  console.log(markdown(summary));
}

function markdown(summary) {
  const f = (s, digits = 2) => (s ? `${s.median.toFixed(digits)} (${s.min.toFixed(digits)}–${s.max.toFixed(digits)})` : '—');
  const lines = [`# Hopper benchmark summary`, ''];
  if (summary.environment) {
    lines.push(`Server ${summary.environment.serverVersion}, Java ${summary.environment.javaVersion}.`,
      `Hopper settings: ${summary.environment.hopperSettings}.`, '');
  }
  lines.push('Median (min–max) across repetitions.', '');
  for (const scenario of [...new Set(summary.groups.map((g) => g.scenario))]) {
    const gs = summary.groups.filter((g) => g.scenario === scenario);
    lines.push(`## ${scenario}`, '', `Fixture: ${JSON.stringify(gs[0].fixture)}`, '');
    const rows = [
      ['runs', (g) => String(g.runs.length)],
      ['mean MSPT', (g) => f(g.metrics.meanMspt, 3)],
      ['p95 MSPT', (g) => f(g.metrics.p95Mspt)],
      ['p99 MSPT', (g) => f(g.metrics.p99Mspt)],
      ['max MSPT', (g) => f(g.metrics.maxMspt, 1)],
      ['mean CPU ms/tick', (g) => f(g.metrics.meanTickCpu, 3)],
      ['p99 CPU ms/tick', (g) => f(g.metrics.p99TickCpu)],
      ['worker drain CPU p99 ms', (g) => f(g.metrics.workerDrainCpuP99Ms, 3)],
      ['worker drain CPU max ms', (g) => f(g.metrics.workerDrainCpuMaxMs, 2)],
      ['worker drains > 2 ms CPU', (g) => f(g.metrics.workerCpuTicksOver2ms, 0)],
      ['mean MSPT CV %', (g) => (g.metrics.meanMspt ? g.metrics.meanMspt.cvPct.toFixed(1) : '—')],
      ['TPS', (g) => f(g.metrics.tps)],
      ['main CPU ms/tick', (g) => f(g.metrics.mainCpuMsPerTick, 3)],
      ['main alloc MB/s', (g) => f(g.metrics.mainAllocMBPerSec, 1)],
      ['items delivered/s', (g) => f(g.metrics.itemsPerSec, 0)],
      ['ChestShop % of main samples', (g) => f(g.metrics.chestShopSharePct)],
      ['worker % of main samples', (g) => f(g.metrics.workerSharePct)],
      ['listener % of main samples', (g) => f(g.metrics.listenerSharePct)],
      ['event construction % of samples', (g) => f(g.metrics.moveEventSharePct)],
      ['worker drain p99 ms', (g) => f(g.metrics.workerDrainP99Ms, 3)],
      ['worker drain max ms', (g) => f(g.metrics.workerDrainMaxMs, 2)],
      ['worker ticks > 2 ms', (g) => f(g.metrics.workerTicksOver2ms, 0)],
      ['probe fill→sign p95 ms', (g) => f(g.metrics.probeInP95Ms, 0)],
      ['probe drain→sign p95 ms', (g) => f(g.metrics.probeOutP95Ms, 0)],
      ['probe timeouts', (g) => f(g.metrics.probeMisses, 0)],
      ['max pending positions', (g) => f(g.metrics.pendingMax, 0)],
      ['max reconciliation chunks', (g) => f(g.metrics.reconciliationMax, 0)],
      ['GC pause ms', (g) => f(g.metrics.gcPauseMs, 0)],
    ];
    lines.push(`| metric | ${gs.map((g) => g.variant).join(' | ')} |`, `|---|${gs.map(() => '---').join('|')}|`);
    for (const [name, fn] of rows) lines.push(`| ${name} | ${gs.map(fn).join(' | ')} |`);
    lines.push('');
    for (const g of gs.filter((g) => g.checks)) {
      lines.push(`**${g.variant} against thresholds:**`, '');
      for (const [k, c] of Object.entries(g.checks)) {
        const value = typeof c.value === 'number' ? c.value.toFixed(2) : String(c.value);
        lines.push(`- ${c.pass ? 'PASS' : 'FAIL'} ${k}: ${value} ${c.unit} (limit ${c.limit})`);
      }
      lines.push('');
    }
  }
  return lines.join('\n');
}

const opts = parseArgs(process.argv.slice(2));
if (opts.summarize) {
  summarize(opts.summarize);
} else {
  mkdirSync(opts.out, { recursive: true });
  console.log(`hopper benchmark output: ${opts.out}`);
  runMatrix(opts);
  summarize(opts.out);
}
