# Explorer coverage and the Vitest 5 migration

The dependency updates in [PR #120](https://github.com/ParadauxIO/hibernia-economy/pull/120)
and [PR #121](https://github.com/ParadauxIO/hibernia-economy/pull/121) must move
`vitest` and `@vitest/coverage-v8` together. The provider at 5.0.5
requires the runner at exactly 5.0.5; updating either package alone leaves an
incompatible peer dependency. Commit the combined npm lockfile and verify it
with `npm ci`. Development and CI need Node 22.12 or later in the Node 22 line
(or another major supported by Vitest).

## Why the branch percentage fell

[PR #202](https://github.com/ParadauxIO/hibernia-economy/pull/202) initially tried
both upgrades. Its [Vitest 5 CI job](https://github.com/ParadauxIO/hibernia-economy/actions/runs/41886292409/job/126771602242)
passed all 1,554 tests, with five environment-dependent skips, then failed the
80% branch gate: 5,535 of 9,022 branches were covered (61.35%). The merged PR
retained Vitest 3, which reported 82.23% branches for the same application and
tests. No application code or tests were removed by the upgrade.

Vitest 3.2.7 defaults to the older `v8-to-istanbul` remapper. Its optional
`coverage.experimentalAstAwareRemapping` was not enabled before this upgrade.
Vitest 4 made AST remapping mandatory, and Vitest 5 retains that behavior.
The AST mapper counts source-level decisions, including decisions in code that
was never executed. The old mapper could report no branches in an unexecuted
module, or count function execution as a covered branch. The percentages have
different numerators and denominators and cannot be compared as a test loss.
See the [Vitest 4 coverage migration](https://v4.vitest.dev/guide/migration.html#v8-code-coverage-major-changes).

A controlled local experiment with the existing Vitest 3 runner illustrates
this distinction: the same 1,284 passing tests reported 3,761/4,491 branches
(83.74%) with the default mapper, and 3,787/8,761 (43.22%) with experimental AST
remapping. The latter also had 12 TSX parse exclusions, so it is diagnostic
evidence only. Use the supported Vitest 5 mapper for the migration baseline.

The upgrade also changes coverage patterns to match paths relative to the
project root more precisely. Compare the file lists as well as totals; see
the [Vitest 5 migration guide](https://vitest.dev/guide/migration/#coverage-include-and-exclude-match-more-precisely).
Keep the existing explicit `include` patterns: they ensure unimported modules
remain visible in the report.

## Accepted baselines and gates

The paired local runs on 2026-11-06 used identical application source and tests
from `bf46b07e`, Node 22.23.3, and matched runner/provider versions. The SQL
suite used disposable MariaDB 11.8.6 with transaction-log fsync disabled and
`innodb_snapshot_isolation=OFF`. CI uses MariaDB 10.11; these are local results,
with the earlier Vitest 5 CI result linked above as separate evidence.

| Runner/provider | Suite | Lines | Statements | Functions | Branches (covered/total) |
| --- | --- | ---: | ---: | ---: | ---: |
| 3.2.7 | Unit + components | 47.68% | 47.68% | 46.07% | 83.74% (3,761/4,491) |
| 5.0.5 | Unit + components | 47.04% | 45.12% | 36.81% | 42.04% (3,793/9,022) |
| 3.2.7 | Unit + components + SQL | 72.98% | 72.98% | 75.73% | 82.21% (5,681/6,910) |
| 5.0.5 | Unit + components + SQL | 71.60% | 69.76% | 65.45% | 61.37% (5,537/9,022) |

Both versions passed 1,284 tests with 275 skips locally and 1,554 tests with
five skips with SQL enabled. All four accepted reports contain the same 344
source files, with no additions, removals or parse exclusions. The small branch
variation from #202's 61.35% is within the gate's headroom. The AST report has
the same branch denominator in both modes, making the added SQL coverage visible.

The thresholds in `economy-explorer/vitest.config.mts` retain roughly two to
three percentage points of headroom around these measured results:

| Mode | Lines | Statements | Functions | Branches |
| --- | ---: | ---: | ---: | ---: |
| Local | 44% | 42% | 35% | 40% |
| `RUN_INTEGRATION=1` | 69% | 67% | 63% | 59% |

The SQL branch margin is 2.37 points, comparable to the old gate's 2.21 points.
The SQL line, statement and function gates increase from 36/36/55 to 69/67/63.
Local function coverage also needs a new baseline: AST mapping counts 3,268
functions instead of 1,782, including callbacks in unexecuted modules.
All existing source inclusions and exclusions are retained; no ignore hints or
automatic threshold updates are added.

Aggregate headroom is supplemented by stricter gates in both modes:

- `lib/auth/admin.ts`, `lib/auth/capabilities.ts` and `lib/auth/routeAccess.ts`
  require 100% of all four metrics **for each file**. The auth glob sets
  `perFile: true` explicitly so Vitest 5 checks each module independently.
- `lib/money.ts` requires 100% lines, statements and functions and 95% branches.
  It currently covers 70/72 branches (97.22%).

The money gate was checked by omitting only the existing division-rounding test
with a test-name filter. All 47 selected tests passed, but branch coverage fell
to 68/72 (94.44%) and the 95% gate failed. Restoring all 48 tests passed the
gate. No test files were changed for this probe. This loss of rounding coverage
fails independently of the global percentage.

## Measurement changes and missing tests

Newly reported uncovered decisions can expose existing test gaps. Check their
reachability and contract before adding tests. A passing suite establishes that
its assertions still hold; it does not establish that every decision has an
assertion. In the local experiment:

- `lib/sql/market.ts` changed from zero counted branches to 182 uncovered
  branches. Local coverage deliberately skips SQL integration tests; CI runs
  them against MariaDB and has its own baseline.
- `lib/auth/delegation.ts` changed from 1/1 branches to 0/5. The local tests
  reject unauthorized mutations before reaching the transaction. The real
  delegation checks run in the SQL integration suite, which covers all 5/5
  branches under Vitest 5. `lib/sql/market.ts` reaches 106/182 branches there;
  the remaining decisions are still gaps to investigate.
- `components/charts/plots/TimeSeriesPlot.tsx` changed from 0/1 to 0/105.
  This is an existing gap in Vitest coverage of chart rendering and its
  conditional states. Browser checks do not contribute to the Vitest report.

The two uncovered money branches also need different treatment: the `sumBy`
selector returning `bigint` is a supported input without direct coverage, while
the private `fixed` helper's zero-digit case has no current caller. Keep these
visible without exposing private helpers or adding ignore hints to reach 100%.

Use uncovered locations to choose tests for meaningful behavior: permission
revocation, delegation limits, failed actions and chart empty/error states.
Do not add calls without assertions merely to increase the percentage.

## Reproduce a comparison

Use identical application source, tests, runtime, database fixtures and coverage
patterns. The pre-upgrade lockfile is available at `bf46b07e`; install each
matched runner/provider pair in its own checkout, or finish one run before
changing installed packages. From `economy-explorer/`:

```bash
npm ci --no-audit --no-fund
npm ls vitest @vitest/coverage-v8 vite
npm run coverage -- --maxWorkers=2 \
  --coverage.reporter=json --coverage.reporter=json-summary \
  --coverage.reporter=text-summary --coverage.reportsDirectory=coverage/local
# Set DB_* to a disposable test database, as in build-and-test.md.
RUN_INTEGRATION=1 npm run coverage -- --maxWorkers=2 \
  --coverage.reporter=json --coverage.reporter=json-summary \
  --coverage.reporter=text-summary --coverage.reportsDirectory=coverage/integration
```

On a busy host, reduce `--maxWorkers` to 1. The accepted Vitest 3 SQL run used
one worker; the accepted Vitest 5 SQL run used two. Initial timing failures
were excluded from the baselines; all accepted runs kept the existing test
timeouts and assertions.

Keep each version's reports separately. `coverage-summary.json` records the
covered/total counts per file; `coverage-final.json` includes branch locations
and hit counts. Confirm test pass/skip counts, file additions/removals, parse
errors, and differences in covered paths before changing a gate. A test failure
or an incomplete report is not a baseline. The normal coverage command writes
these JSON reports alongside HTML and LCOV, and CI retains reports on failure.
