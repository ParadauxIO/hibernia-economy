# Economy Explorer verification

The redesign uses summary tables for historical aggregates and bounded ledger
reads for transaction lists. The [design specification](../economy-explorer/design/redesign-spec.md#11-performance-budgets-and-verification)
defines the release budgets; the [user guide](../economy-explorer/docs/guides/using-the-explorer.md)
explains the resulting pages and access rules.

## Repeatable checks

Run the Explorer typecheck, lint and Vitest suite from `economy-explorer/`.
Integration tests require a disposable MariaDB database through `DB_*` and
`RUN_INTEGRATION=1`. Never point the managed Playwright suite at a live database:
its global setup resets the database named by `DB_NAME`.

`npm run test:e2e` builds a production app with disposable test identities. The
suite exercises desktop and mobile navigation, role and relationship boundaries,
exports, keyboard interactions and axe accessibility checks. Fresh-deploy tests
run before summary tables are populated; the rollup project then builds history
for the remaining tests.

- `test/e2e/javascript-budget.spec.ts` checks the modern-browser initial scripts
  emitted by the production build against `ops/javascript-budget.json` (170 KiB
  using Brotli). It excludes `nomodule` polyfills and deferred chart imports,
  rejects an eagerly loaded Recharts implementation, and attaches measured bytes.
  This is a deterministic compressed artifact budget, not a claim about the
  compression configured on every reverse proxy.
- `test/e2e/charts.spec.ts` delays the chart-library response and compares plot
  geometry before and after loading on `/`, an account and an item page, in
  addition to the chart-kit fixtures.
- `test/integration/gov.test.ts` counts actual Kysely queries through the request
  timing instrumentation. A cold overview, including scope resolution, must stay
  within ten reads. Historical and narrowed-unit balance assertions protect
  against subtracting the wrong accounts or missing later postings.

## Performance evidence

Inspect `Server-Timing` for database time and query count. Measure the public
account page anonymously, and use authorized identities for private account,
`/me` and `/gov` measurements: a fast access gate is not a workspace measurement.
Record the revision, dataset, cache state, sample count and timing definition.
The anonymous `ops/perf-baseline.mjs` script records route timings and scripts as
served. Its first request is not proof of a cold cache.

A local combined production build was checked on 2026-10-26 with the shared E2E
fixtures plus government fixtures, populated rollups, and 20 warm requests after
one warm-up per route. Client-observed response-completion p95 was 26.8 ms for
public `/accounts/3`, 42.8 ms for its admin view, 38.4 ms for `/me`, and 56.1 ms for
admin `/gov`. These are small-fixture regression measurements, not production
capacity results. The cold government integration check initially failed at
13 queries; consolidating existing scope balances, daily nets and transaction
bounds brings it within the ten-query limit.

For deployment checks, use a new dev pod for cold-process measurements and inspect
`/healthz?rollup=1` for worker freshness and coverage. Do not flush a shared Redis
database. Any deliberate cache eviction must be restricted to the dev Explorer's
cache keys and preserve its worker lease. Verify migrations through the approved
plugin deployment workflow, retaining the previous approved bundle for rollback.

## Retention and real-user follow-up

When both Umami configuration variables are present, `explorer-follow-through`
records client-side navigation with `from` and `to` section names. It sends no
entity ID, player/firm name or query filter. No extra analytics SDK is loaded.
Use Umami's session report for the single-page-session target (below 75%); event
counts alone are not a bounce-rate calculation.

Review Faro p75 LCP (at most 1.8 s) and INP (at most 200 ms) two weeks after release.
Those outcomes cannot be established by local tests or immediately after deploy.
Previous-period chart comparison remains hidden until the charts support the
comparison series. Money flow is limited to 92 days; expanding it needs a summary
that pairs account types directly.
