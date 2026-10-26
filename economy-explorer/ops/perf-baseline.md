# Economy Explorer performance baseline

This file is the reference point for the redesign's performance work (spec §4.1 and §11, PAR-531). Every later performance PR quotes its before and after figures against these numbers.

It has two parts:

1. **Outside-in timings**, measured on 2026-10-26 with `curl` before any optimisation landed. They are below.
2. **Server-side percentiles** from the new Prometheus metrics. These can only be collected once this PR is deployed to dev; [After deploy](#after-deploy-server-side-percentiles) lists the queries to run and the empty table to fill in.

## What was measured

| | Dev | Production |
|---|---|---|
| URL | https://economy.dev.paradaux.io | https://economy.democracycraft.net |
| Build | `development-sha-54e68f0`: `develop` at the merge of PR #109. That PR is docs-only, so the app code is the same as `5273d32`. | `production-sha-36d7d90`: `main`, 2026-06-23. An older build: no `/economy/*` routes, and the wealth panels are still on `/`. |
| Data | A production clone (DB refreshed 2026-10-17) | Production |
| Traffic | Almost none, so caches are often stale or cold | Live players |

Image tags come from the gitops overlays (`apps/economy-explorer/overlays/{development,production}`).

**Method.**
- `node ops/perf-baseline.mjs https://economy.dev.paradaux.io https://economy.democracycraft.net`, run from the paseo build host.
- 5 anonymous GETs per route, with `Accept-Encoding: br, gzip`. The requests are interleaved, so each site saw one request about every 2.5 s.
- **First** is a route's first request in the run: the closest an outside client gets to a cold cache. The other four are **warm**.
- TTFB is curl's `time_starttransfer`, and total is `time_total`.
- **HTML raw / wire** is the decoded document size and the bytes actually transferred.

**Limits.**
- Five samples per route can't give a meaningful p95 or p99. The percentiles the spec asks for come from `explorer_render_seconds` after deploy.
- The two sites sit behind different network paths from the measuring host. Compare a site only with itself across runs, never dev against production in absolute terms.
- Only anonymous pages can be measured this way. `/me`, `/gov` (today `/government` with permissions), `/accounts/:id` with history visible and `/transactions` need a session. They will be measured from the server-side metrics after deploy.

## Headline numbers

- **A cold pod fails on `/`.**
  - The first request to dev `/` at 04:18 UTC took **15.5 s** (TTFB 0.44 s). The pod had just been rolled to `54e68f0`.
  - The stream ended in a server render error (digest `1677701082`), and the body was 7.7 KiB on the wire against 23.5 KiB when warm.
  - This matches spec §1.3: the cold bridge-pair aggregate runs into the 15 s `max_statement_time`.
  - Every later request was served from the stale-while-revalidate cache.
- **Warm anonymous pages are fast.** Most warm TTFBs are 30–50 ms on dev and 60–110 ms on production. Totals are under 300 ms, apart from the routes below.
- **Slow routes:**
  - `/search?q=…` takes 0.49 s (dev) and 0.60 s (production) to complete, although its first byte arrives in under 0.1 s.
  - Production `/government` sends **310 KiB** of HTML to anonymous visitors, with a 346 ms median and one sample at 1.0 s. Dev gates the page and sends 34 KiB.
  - Production `/accounts/:id` takes about 270 ms in total against about 50 ms on dev.
- **JavaScript on `/`:**
  - Dev: 12 scripts, **335 KiB compressed (1,105 KiB raw)**. The largest is 406 KiB raw.
  - Production: 296 KiB compressed (1,001 KiB raw), which matches the spec's 294 KiB.
  - The spec §2 budget is ≤ 170 KiB compressed for first paint.

## Route timings

Measured 2026-10-26 04:36 UTC: 5 samples per route, 2.5 s apart. Times are in ms.

### Dev (economy.dev.paradaux.io, `54e68f0`)

| Route | Status | First TTFB | First total | Warm TTFB, median (min–max) | Warm total, median (min–max) | HTML KiB raw / wire |
|---|---|---|---|---|---|---|
| `/` | 200 | 52 | 184 | 39 (31–49) | 71 (62–83) | 133.7 / 23.5 |
| `/accounts` | 200 | 60 | 177 | 31 (31–102) | 109 (106–201) | 156.4 / 26.1 |
| `/accounts/35156` | 200 | 83 | 161 | 48 (42–84) | 54 (48–102) | 39.7 / 9.8 |
| `/accounts/146686` | 200 | 58 | 64 | 40 (36–110) | 44 (39–116) | 32.7 / 7.4 |
| `/firms` | 200 | 50 | 147 | 41 (30–73) | 98 (79–140) | 167.6 / 26.9 |
| `/firms/TSEGovtBank` | 200 | 58 | 60 | 38 (31–58) | 42 (38–64) | 38.6 / 9.4 |
| `/market` | 200 | 33 | 81 | 35 (34–41) | 64 (38–75) | 84.6 / 21.1 |
| `/economy/wealth` | 200 | 53 | 77 | 31 (29–45) | 48 (43–61) | 59.2 / 12.8 |
| `/economy/taxation` | 200 | 78 | 90 | 38 (37–77) | 58 (51–87) | 56.0 / 12.8 |
| `/government` | 200 | 47 | 47 | 41 (40–59) | 41 (41–60) | 34.3 / 8.2 |
| `/money-flow` | 200 | 51 | 64 | 36 (34–65) | 49 (47–65) | 49.2 / 11.2 |
| `/search?q=Planke` | 200 | 71 | 1,574 | 39 (33–48) | 491 (462–569) | 40.8 / 8.6 |
| `/chestshop/sales` | 200 | 55 | 55 | 35 (33–52) | 37 (35–53) | 35.0 / 8.4 |
| `/docs` | 200 | 129 | 192 | 85 (84–143) | 99 (87–148) | 54.7 / 14.7 |

The cold `/` request described in the headlines was made before this run, so it is not in this table.

### Production (economy.democracycraft.net, `36d7d90`)

| Route | Status | First TTFB | First total | Warm TTFB, median (min–max) | Warm total, median (min–max) | HTML KiB raw / wire |
|---|---|---|---|---|---|---|
| `/` | 200 | 98 | 179 | 87 (77–96) | 130 (120–156) | 154.6 / 28.5 |
| `/accounts` | 200 | 106 | 134 | 111 (91–113) | 131 (121–146) | 114.7 / 23.0 |
| `/accounts/35156` | 200 | 81 | 275 | 77 (57–100) | 267 (255–284) | 39.6 / 10.8 |
| `/accounts/146686` | 200 | 82 | 270 | 86 (69–99) | 280 (268–291) | 38.9 / 10.5 |
| `/firms` | 200 | 81 | 110 | 97 (85–106) | 124 (122–131) | 114.2 / 23.2 |
| `/firms/TSEGovtBank` | 200 | 61 | 153 | 82 (75–93) | 169 (156–177) | 37.9 / 10.3 |
| `/market` | 200 | 95 | 132 | 79 (67–154) | 94 (90–188) | 87.9 / 19.7 |
| `/economy/wealth` | 404 | 61 | 78 | 85 (62–90) | 106 (81–114) | 28.0 / 8.4 |
| `/economy/taxation` | 404 | 68 | 86 | 83 (72–114) | 101 (89–139) | 28.0 / 8.4 |
| `/government` | 200 | 142 | 225 | 112 (101–222) | 346 (341–1,015) | 309.8 / 68.2 |
| `/money-flow` | 200 | 142 | 162 | 82 (70–90) | 99 (91–117) | 55.2 / 13.8 |
| `/search?q=Planke` | 200 | 102 | 591 | 84 (68–96) | 601 (560–671) | 41.4 / 9.9 |
| `/chestshop/sales` | 200 | 108 | 122 | 77 (76–86) | 92 (91–105) | 35.2 / 9.7 |
| `/docs` | 200 | 99 | 1,102 | 65 (59–81) | 219 (192–396) | 52.5 / 15.2 |

`/economy/*` does not exist on the production build; the 404s are listed so later runs line up.

Account `35156` is the largest personal balance; `146686` was the newest account at the time of the run. Both show the anonymous view: summary cards, with no history.

## JavaScript for `/`

These are the scripts referenced by the server HTML of `/`: `<script src>` plus `<link as="script">` preloads. Each was fetched once.

| Site | Scripts | Compressed KiB (as served) | Raw KiB | Largest script, raw KiB |
|---|---|---|---|---|
| Dev (`54e68f0`) | 12 | 335.3 | 1,105.1 | 406.0 |
| Production (`36d7d90`) | 14 | 296.2 | 1,000.7 | 414.3 |

This count does not include chunks that are loaded later with `import()`. The §11 CI budget check will measure first-paint JS from the build output instead.

## After deploy: server-side percentiles

Once this PR is on dev, leave it to collect traffic for at least a day. Then record p50, p95 and p99 per route from Prometheus here. Use the instant queries below over the whole window; `kind="document"` means full page loads only.

```promql
histogram_quantile(0.95, sum by (le, route) (rate(explorer_render_seconds_bucket{tenant="dev", kind="document"}[1d])))
histogram_quantile(0.95, sum by (le, route) (rate(explorer_render_db_queries_bucket{tenant="dev", kind="document"}[1d])))
topk(15, sum by (fn) (rate(explorer_db_query_seconds_sum{tenant="dev"}[1d])))
```

Replace `0.95` with `0.5` and `0.99` for the other columns. Run the same queries with `tenant="democracycraft"` once production carries this build.

| Route | p50 | p95 | p99 | Queries per request, p95 | §11 budget |
|---|---|---|---|---|---|
| `/accounts/[accountId]` (public) | | | | | ≤ 3 queries, p95 ≤ 150 ms |
| `/accounts/[accountId]` (with access) | | | | | ≤ 8 queries, p95 ≤ 400 ms |
| `/me` | | | | | ≤ 8 queries, p95 ≤ 400 ms |
| `/government` → `/gov` | | | | | ≤ 10 queries, p95 ≤ 600 ms |
| `/` | | | | | first byte ≤ 2 s cold |

The metrics label routes by pattern, not by viewer, so `/accounts/[accountId]` covers public and with-access views together. To split them, compare its query-count histogram with the public-only figure from a logged-out run.

## Re-running

```bash
cd economy-explorer
node ops/perf-baseline.mjs https://economy.dev.paradaux.io https://economy.democracycraft.net
# Optional: SAMPLES=5 SPACING_MS=2500 ROUTES=/,/accounts,...
```

Keep the defaults: they are the settings used above, and they hold each site to about one request every 2.5 s.
