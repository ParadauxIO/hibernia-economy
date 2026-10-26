# Economy Explorer redesign: specification

Status: decisions recorded (§14), 2026-10-26. Ready to break into Tesks tickets.
Supersedes nothing. Builds on [`design-audit.md`](design-audit.md); its agreed "public data dashboard" direction still holds.

This redesign is the last piece of polish before `develop` is released. Production stays on its current build until the redesign lands.

This spec covers five goals:

| # | Goal | Where it is addressed |
|---|---|---|
| a | Look nicer | §5 Design system v2, §6 Shell, every page section |
| b | Be more powerful | §5.5 Tables with filter chips, §9 Power tools, plus new views in §7, §8 and §10 |
| c | Be faster | §4 Data platform, §11 Performance budgets |
| d | Better logged-in view of your own finances | §8 My finances |
| e | Better for governments with permissions | §10 Government workspace |

The work is split into workstreams with explicit file ownership and dependencies (§13), so it can be handed to parallel agents.

---

## 1. Evidence

All figures were measured on 2026-10-26 unless stated otherwise.

### 1.1 Who uses it and how (Umami, economy.democracycraft.net, last 30 days)

- 5,738 sessions and 16,261 page views. **87% of sessions view exactly one page** (4,992 of 5,738).
- 93% of sessions have no referrer, and `/accounts/:id` is the top page by far. Most visits are someone clicking an account link in game, looking once, and leaving.
- 92% of sessions are laptop or desktop, and 5% are mobile.

| Route | Views | Sessions |
|---|---|---|
| `/accounts/:id` | 5,834 | 2,946 |
| `/` | 1,937 | 801 |
| `/accounts` | 1,554 | 426 |
| `/me` | 1,244 | 322 |
| `/firms` | 814 | 276 |
| `/market` | 525 | 225 |
| `/search` | 276 | 152 |
| `/government` | 128 | 70 |
| `/me/market` | 140 | 41 |

**What this means for the design:** the account page is the front door. When a player arrives logged out from a game link, the page must be useful on its own and give an obvious next step. Today it shows four cards and a lock (§7.1).

### 1.2 Data volume (production MariaDB)

| Table | Rows | Size |
|---|---|---|
| `ledger_postings` | 19.2M | 3.3 GB |
| `ledger_txns` | 9.3M | 2.7 GB |
| `chestshop_sale` | 3.3M | 1.7 GB |
| `accounts` | 139k | 35 MB |

- About 35k transactions settle per day.
- Over the last 30 days, transactions by plugin were: ChestShop 949k, treasury-salary 130k, Treasury-Core 71k, DemocracyRobbery 31k, Exams 19k, rest-api 15k, realty 8k, and a long tail. `plugin_system` is therefore a usable categorisation key.
- No transactions are unsettled (`settlement_time IS NULL` = 0 over the last 200k).

### 1.3 Performance

- **Anonymous production pages are already fast when warm.** Time to first byte is 70–110 ms on `/`, `/accounts/:id`, `/firms` and `/market`. `/government` is 0.89 s total for 317 KB of HTML, and `/economy/health` has a 370 ms TTFB.
- **Cold paths are not fast.** On dev, the first request to `/` today took about 18 s and ended in React error #441, the generic server render error. That matches the 15 s statement timeout on the bridge-pair CTE (see the data audit below). The next request was served stale-while-revalidate in 0.2 s.
- **The heavy queries run where the logged-in traffic is.** The data audit's ranked risks are:
  - The account page computes all-history counterparties and a 365-day calendar per `(id, page)`. The code comment says about 15 s uncached for popular accounts.
  - `/me` is entirely uncached and checks access once per account (N+1).
  - `ledger_postings` has no time column, so every per-account window walks the account's full history.
  - The bridge-pair CTE is recomputed three times per refresh.
  - The L1 cache is unbounded and has no cold-path lock.
- **The client ships too much JavaScript.** `/` loads 294 KiB of JS compressed (996 KiB raw). One 109 KiB chunk is recharts, and the charts do not render until it hydrates.
- **There is no server-side timing at all**: no Server-Timing headers, no per-query metrics, no cache hit/miss counters.

### 1.4 Visible defects found during research

Some of these were seen on production, which is running an older build than `develop`. Production's `/` still shows the wealth panels that `develop` moved to `/economy/wealth`. **Re-check each item on `develop` before fixing it.**

1. The "Share" column of Balance summary by type shows 0.0% on every row (production).
2. Fine categories are the first word of the reason (`SUBSTRING_INDEX`, `lib/sql/government.ts:94`). Players write the reasons, so this produces junk categories such as `c027-c028`, `.` and `rh021`. **Decision: remove the categories.** Fines get a searchable, chip-filtered table instead (§5.5).
3. Chart left-axis labels are clipped (`40,000` shows as `0,000`).
4. The weekday×hour heatmap uses half the card width on `develop` and is cut off after hour 21 on production.
5. Spline curves overshoot below zero.
6. One outlier day flattens the whole volume chart on the dev clone ($999B volume). There is no outlier handling.
7. The account page shows the owner as a raw UUID, and the created date has no year.
8. `/me` "Total balance" adds personal, firm and government money together. Its income and spend count transfers between the player's own accounts. **Decision: fix** (§8.1).
9. `parseFloat` is used for money sums on `/government`, `/money-flow` and `/me/market`. The rest of the app uses exact decimals.
10. When `getViewer` fails it returns `permissions: []`, which is less than the player baseline its own comment promises.
11. The Government tab is shown to everyone, so most players land on a gate.
12. `<td className="empty">` is used in 22 files, but no `.empty` CSS rule exists.

**Not a defect:** the Government "Gov treasury" total is −$157M because it includes the minting accounts (`TreasuryIngest` −$224.9M, `Eco` −$26.6M, `starting-balances` −$1.0M). That is correct for a pure ledger: the account that minted the money holds the negative balance. The spec does not hide or re-label these accounts.

### 1.5 State of the frontend code (UI audit)

- **A design-system package exists, but pages mostly bypass it:**
  - `PageHeading` is used in 3 files; raw `page-heading` markup in 39.
  - `StatCard` in 2 files; hand-rolled `.kpi` in 12.
  - `Card` in 1 file; raw `card` markup in 37.
- **There is no type scale and no spacing scale.** The CSS uses 25 distinct `font-size` values and 10 different literal radii. There are 235 inline `style={{}}` attributes.
- **`explorer.css` is 1,588 lines**, including about 200 lines of dead "back-compat" CSS and duplicated selectors.
- **Patterns differ from page to page.** Sorting, export, window tabs and empty states each work differently. Exports cover only the current page.
- **Dead or orphaned code:** `SubNav`, `ComingSoon`, `MoneyFlowDiagram`, `Sparkline`, `TopAccountsBar`, `/chestshop/firms` (no nav entry), `/design-system` (not linked).

### 1.6 State of auth and government features (auth audit)

- **Login:** Keycloak through Auth.js. The Minecraft identity is linked with an in-game code or a token claim.
- **Authorization** keeps RBAC and ReBAC as separate layers:
  - RBAC: 44 permission keys, roles, and groups, including LuckPerms-synced groups.
  - ReBAC: the `account_read_access_web` and `firm_financial_access_web` views.
  - **This separation is deliberate and this spec keeps it** (§3).
- **Government features today:**
  - Production has three explorer groups: Department of Commerce, Moderator and Admin.
  - `/government` shows KPIs, an accounts list and a fines list.
  - There is no spending breakdown, no reporting, no exports, no grouping of government accounts into departments, and no audit view.
  - Reads of government accounts through a relationship are not audited.
- **Admin writes:** treasury-rest-api receives one shared service token with no actor attached. The audit log is fail-open and has no UI.

---

## 2. Goals and success criteria

These are measurable targets. Each workstream's acceptance criteria roll up into them.

| Goal | Criterion |
|---|---|
| Faster | No request fails because a cache was cold. The first request to any public page on a cold pod returns in under 2 s. |
| Faster | Server render p95 for `/accounts/:id` with history and for `/me` is ≤ 400 ms; p99 is ≤ 1.5 s. Both are measured from the new Server-Timing and Prometheus metrics. |
| Faster | The chart library is loaded only on routes that draw charts, and only after first paint. JS needed for first paint on `/` is ≤ 170 KiB compressed (294 KiB today, 109 KiB of it recharts). Routes without charts never load recharts. |
| Faster | LCP p75 ≤ 1.8 s and INP p75 ≤ 200 ms, measured with Faro RUM. |
| Faster | `/me` makes ≤ 8 DB round trips (about 13 today, more with several accounts). |
| Nicer | Every page is built from design-system primitives. `explorer.css` is ≤ 500 lines. Inline `fontSize` is gone. |
| Nicer | All four theme × mode combinations (blue/red × dark/light) pass the existing overflow and axe suites. |
| Powerful | Every table uses the one `DataTable` + filter-chip system (§5.5). Filters and search live in the URL, so any filtered view can be shared. Every table can be exported as a full result (not just the current page) within the caller's permissions. |
| Nicer | No chart bugs: no clipped axis labels, no lines overshooting below zero, no chart flattened by one outlier, and the heatmap fills its card. |
| Powerful | Every chart has a "view data" table and a shareable URL state. |
| Own finances | `/me` separates personal money from business and government money. It shows income and spending by category, tax paid, fines received, a full searchable history and monthly statements. |
| Government | An official sees their department's accounts grouped and totalled, with revenue, spending, payroll, fines and monthly reports, all scoped by the existing ReBAC. |
| Retention | Single-page sessions drop below 75%, measured in Umami (87% today). |

---

## 3. Constraints and principles

These are not negotiable, and every agent brief must restate them.

1. **The explorer is read-only against the economy.**
   - Money moves only through treasury-rest-api admin endpoints, as today.
   - The explorer writes only to `explorer_*` tables.
   - New derived tables are named `explorer_rollup_*` and `explorer_*`.
2. **No new deployables.** Everything runs in the plugins, treasury-rest-api, or the economy explorer itself. That includes background work such as the rollups (§4.2). No CronJobs, sidecars or standalone workers.
3. **Schema changes go through Flyway** as `economy-flyway/.../V<n>__*.sql`. Take the next free number at merge time, because parallel branches will collide.
   - No trigger may lock a shared aggregate row on the ledger write path.
   - V36 explains why: shared rows serialise transfers and can deadlock against the balance `FOR UPDATE` locks.
   - Aggregates are therefore built asynchronously (§4.2).
4. **RBAC and ReBAC stay separate.**
   - Permissions say what kind of thing you may do.
   - Account and firm relationships say which resources you may do it to.
   - New concepts such as government units (§10.2) are presentation groupings. They are not a third access model.
   - An official sees a unit's data because they can read its accounts, and for no other reason.
   - Elevated capabilities, such as the compliance tools (§10.4), reach people through the existing LuckPerms group sync → explorer group → role path. Nothing is hard-wired to a specific group.
5. **A business's money is not its owner's money.** Firms can have shareholders, and the largest firms are banks holding other players' deposits.
   - Never add business balances into a player's totals, and never present a firm's balance as belonging to its proprietor.
   - On business pages the firm comes first. The proprietor is shown as the firm's proprietor, not as the owner of the funds.
6. **Layering:** Server Component page or route → `lib/services/*` → `lib/sql/*`. The SQL layer is pure Kysely queries with no business logic.
7. **Multi-tenant.** The same build serves DemocracyCraft (`THEME=blue`) and StateCraft (`THEME=red`, economy.mcstatecraft.com).
   - Branding comes only from tokens and `serverIdentity`.
   - Nothing may be DemocracyCraft-specific in code, including the names of government accounts.
8. **Money is exact.**
   - Sums are computed with SQL `DECIMAL`, or with integer cents (`bigint`) in JS. `parseFloat` and `Number` are never used for money.
   - A single `lib/money.ts` owns parsing, arithmetic and formatting.
   - The ledger is shown as it is. Minting accounts keep their negative balances and are included in totals; nothing is hidden or re-labelled to make totals look nicer.
9. **Privacy defaults stay as they are**, except for the decisions recorded in §14:
   - Public: balances, account metadata, firm directory, market aggregates, taxation aggregates, and (new, §14 Q2) activity totals for GOVERNMENT and BUSINESS accounts.
   - Private: personal transaction history, firm rosters and financials, sales attribution.
10. **Accessibility is a gate.** The existing axe suite (`test/e2e/a11y.spec.ts`) must stay green, and new routes are added to it.
11. **Process.**
   - Branch and open a PR into `develop`, which is protected by ci-gate.
   - Every commit subject cites its Tesks ref (`PAR-…`).
   - Commits have no co-author trailers.
   - Player-facing changes update `economy-explorer/docs/`. Developer-facing changes update `wiki/`.

---

## 4. Data platform (workstream D)

This is the base for both speed and the new features. The aim is to get heavy aggregation out of the request path.

### 4.1 Instrumentation (do first)

| Item | Detail |
|---|---|
| `instrumentation.ts` | Registers a Kysely plugin that times every query and labels it with the calling DAL function name. Metrics: `explorer_db_query_seconds{fn}` histogram and `explorer_db_queries_total{fn,outcome}`. |
| Cache metrics in `lib/cache.ts` | `explorer_cache_requests_total{key_prefix,result=hit_l1\|hit_redis\|stale\|miss\|refresh_fail}` and `explorer_cache_refresh_seconds{key_prefix}`. |
| Render timing | Per-request `Server-Timing` header with `db;dur=`, `cache;desc=hit`. Also `explorer_render_seconds{route}`. |
| Endpoint | `/metrics` (prom-client), reachable only from the cluster. Reuse the existing `requestBlocking` / proxy matcher to keep it internal. |
| Dashboard | A Grafana dashboard JSON committed under `ops/`. |
| Baseline | `ops/perf-baseline.md` records p50, p95 and p99 per route before any optimisation lands. Every later performance PR quotes before and after against this file. |

### 4.2 Rollup worker

The rollup worker runs **inside the economy explorer's own Node process**, following §3 rule 2 (no new deployables). The code lives in `lib/rollup/`.

**How it runs**
- **Started from `instrumentation.ts`** `register()` when `NEXT_RUNTIME === 'nodejs'`. It can be disabled with `ROLLUP_WORKER=off`, for local dev and tests.
- **Exactly one pod runs it at a time,** chosen through a Redis lease: `SET ee:<tenant>:rollup:leader <pod> NX PX 30000`, renewed every 10 s. If the leader dies, another pod takes over within 30 s.
- **The database does the heavy work.** Each step is an `INSERT … SELECT … GROUP BY` statement, so Node only issues statements and almost no aggregation runs on the JavaScript event loop that serves requests.
- **It has its own small connection pool** (2 connections) with a longer statement timeout than request queries. It can never starve request traffic of connections.
- **It owns the `explorer_rollup_*` tables and is the only thing that writes them.** Writing `explorer_*` tables is already within the explorer's remit (§3 rule 1).

**What each run does**
- **Idempotent day recompute, no watermark.**
  - Every minute, it recomputes rows for the current UTC day and the previous one. It deletes those days and re-inserts them in one transaction per table.
  - Once a night, it recomputes the last 7 days, which absorbs any late `settlement_time` updates.
- **Progressive, resumable backfill.** No separate backfill job exists.
  - When the worker has spare time, it works backwards from yesterday, one day per transaction.
  - Progress is recorded in `explorer_rollup_coverage(job, from_day, to_day)`. A restart resumes where it stopped.
  - Newest-first order means recent ranges, which are what pages ask for most, become available first.
  - Readers check coverage. A section whose range is not covered yet shows a "Preparing history: covered back to <date>" state, never a slow live query. This is a single code path, not a fallback to the old queries.
  - On production the backfill runs once at release. D1 measures how long it takes on the dev clone and records the figure in the release runbook.
- **Cost:** about 35k transactions and 70k postings per day. The worker reads them through the `settlement_time` / `ledger_txn_volume` indexes.
- **Freshness contract:** rollups lag by at most about 5 minutes (`ROLLUP_TICK_MS`).
  - Pages that use them say "updated hh:mm".
  - "Today so far" views may add a cheap live tail query for after the last rollup run.
- **Health:** the worker writes `explorer_rollup_run(job, day, started_at, finished_at, rows, error)`.
  - `/healthz` reports staleness and backfill coverage.
  - An alert fires if the newest run is more than 10 minutes old.
- **Wealth snapshots** (`explorer_snapshot_wealth_day`) start from the first run. Earlier days are not reconstructed.

**Tables.** Final columns are up to the implementer; keys and grain are fixed.

| Table | Grain / PK | Feeds |
|---|---|---|
| `explorer_rollup_account_day` | `(account_id, day, plugin_key, tax_kind)` → `inflow`, `outflow`, `txn_count` | Account and `/me` charts, category breakdowns, `/gov` revenue and spending, firm totals (joined through `firm_accounts`) |
| `explorer_rollup_pair_month` | `(account_id, counterparty_id, month)` → `inflow`, `outflow`, `txn_count` | Counterparties, which replaces the all-history self-join (`ledger.ts:246`) |
| `explorer_rollup_economy_day` | `(day)` → bridge-adjusted volume and count, raw volume and count, active players, new players | Home overview and health. **Removes the bridge-pair CTE from the request path.** |
| `explorer_rollup_type_day` | `(day, account_type, plugin_key)` → `inflow`, `outflow` | Money supply over time by account type, money flow |
| `explorer_rollup_hour_heatmap` | `(day, weekday, hour)` → `txn_count` | The heatmap, which today reads raw `ledger_txns` |
| `explorer_rollup_item_day` | `(item_key, day, direction)` → `qty`, `volume`, `trades`, `min`, `max`, `open`, `close` unit price | Market charts and candles, popular items, catalogue stats |
| `explorer_snapshot_wealth_day` | `(day)` → supply by type, Gini, top 1% and 10% shares, holder buckets | Wealth history, which today is a point-in-time value only |

**Balance history** for an account is derived without a snapshot table. The balance at the end of day *d* equals the current balance minus the net change of every later day. A service helper, `balanceSeries(accountIds, range)`, builds it from `explorer_rollup_account_day`.

**`plugin_key`** is `COALESCE(ledger_txns.plugin_system, '')` as stored. Categories are mapped at read time (§4.4), so changing the taxonomy never needs a backfill.

### 4.3 Request-path fixes

| Fix | Detail |
|---|---|
| Request dedupe | Wrap `findAccount`, `findFirm`, `canReadAccount`, `hasFirmFinancialAccess`, `isFirmMember` and identity lookups in React `cache()`. Replace the N+1 in `PlayerDashboard.tsx:81` with one batched `readableAccountIds(viewer, ids)`. |
| Cache hardening (`lib/cache.ts`) | Bound the L1 cache with an LRU (about 5k entries and a byte cap). Add a cross-pod lock on cold misses: one pod computes and the others poll Redis for up to the lock TTL, then compute themselves. Add a `maxStaleMs` per key so data can never be 7 days stale on a quiet key. Count `refresh_fail` (§4.1). |
| Keyset pagination | Replace OFFSET pagination with a cursor `(settlement_time, txn_id)` or `(occurred_at, sale_id)` for ledger, sales and fines lists. The UI shows Newer / Older plus "jump to date" instead of page numbers on unbounded feeds. Directory lists (accounts, firms), which are bounded, may keep page numbers. |
| No total counts on big tables | `/transactions` shows "1,000+" style counts estimated from `explorer_rollup_economy_day`. |
| Search | Covered in §9.1. Typeahead uses index-friendly prefix matching only. |
| Streaming | Every data page renders its shell immediately and wraps each data section in `<Suspense>` with a skeleton that matches its final size. No page awaits all of its data before sending bytes. |
| Viewer in layout | Resolve the viewer on the server once in the root layout with the cached `getViewer`, and hydrate `ViewerContext` from props. This removes the `/api/viewer` round trip on every document. Keep the route for client refresh after login. |
| AutoRefresh | Skip refresh when `document.visibilityState !== 'visible'`. Add ±10 s jitter. Refresh only the live sections through a server action or route segment instead of `router.refresh()` of the whole tree. Drop the 1 s re-render timer in favour of CSS or relative-time updates every 30 s. |
| Faro | `import()` the SDK only when `FARO_COLLECTOR_URL` is set. |
| Exact money | `lib/money.ts` replaces every `parseFloat` sum (§1.4 item 9). |

### 4.4 Category taxonomy

`lib/categories.ts` is pure, unit-tested and shared by `/me`, the account page and `/gov`.

- **Inputs:** `plugin_key`, `tax_kind`, the counterparty account type, and the direction relative to the subject.
- **Output:** one of the categories below.

| Category | Source |
|---|---|
| Wages & salary | `treasury-salary` |
| Shop sales, Shop purchases | `ChestShop`, split by direction |
| Taxes | `tax_kind`, split by kind |
| Fines | Linked `government_fines.txn_id`, or `tax_kind` = fine |
| Property | `realty` |
| Business | `BusinessPlugin` |
| Crime & bounties | `DemocracyRobbery`, `BountyHunters` |
| Jobs & activities | `Exams`, `nightcore`, `CustomFishing`, … |
| Transfers | `Treasury-Core` / `treasury` between players |
| API | `rest-api` |
| Internal | Between the subject's own accounts. Excluded from income and spending. |
| Other | Fallback |

Rules for the mapping:

- The plugin→category map is data, not code paths: a typed table in the same file.
- Unknown plugins fall into Other and are logged once, so new plugins show up for a mapping decision.
- StateCraft may have different plugins. The map is shared, and Other is an acceptable fallback.

---

## 5. Design system v2 (workstream DS)

Direction: an evolution of the agreed "public data dashboard". It is not a rebrand.

- Dense, calm, number-first. Think Stripe dashboard or a public-finance portal, not a marketing site.
- Light Minecraft flavour: player heads and item icons, never textures or pixel fonts in body text.

### 5.1 Tokens (`packages/design-system/styles/tokens.css`)

**Type scale**

| Token | Size / line height | Use |
|---|---|---|
| `--text-xs` | 12/16 | Captions, axis labels |
| `--text-sm` | 13/18 | Table body, secondary text |
| `--text-md` | 14/20 | Body |
| `--text-lg` | 16/24 | Card titles |
| `--text-xl` | 20/28 | Section headings |
| `--text-2xl` | 24/32 | KPI values |
| `--text-3xl` | 32/40 | Page titles |

- Weights: 400, 500 and 600 only.
- `font-variant-numeric: tabular-nums` on every numeric cell and KPI.

**Spacing:** a 4 px base, `--space-1` through `--space-12` (4, 8, 12, 16, 20, 24, 32, 40, 48, 64). Keep `--section-gap` as an alias of `--space-6`.

**Radius:** `--radius-sm` 6, `--radius-md` 10, `--radius-lg` 14, `--radius-pill` 999. Nothing else.

**Elevation:** `--shadow-1` and `--shadow-2` for popovers and the command palette only. Cards use borders, not shadows.

**Semantic colour** (on top of the existing surfaces and brand):
- `--pos` and `--neg` for money direction, separate from the status colours.
- `--cat-1` through `--cat-10` for categories.
- `--seq-*` for heatmaps and other sequential scales.

Every new colour must pass contrast in all four theme × mode blocks. The red theme keeps its no-green rule for chart series.

### 5.2 Primitives

These are new or reworked components exported from `@democracycraft/design-system`. The `/design-system` gallery shows every one of them in every state.

| Primitive | Notes |
|---|---|
| `PageHeader` | Title, subtitle, entity badge, actions slot and tabs slot. Replaces both `PageHeading` and raw `.page-heading`. |
| `Section` | `h2` title, description, actions and the content. Replaces `card-title` divs, of which there are 64. Gives a real heading hierarchy. |
| `MetricCard` | Label, value in `Money` or `Num` form, delta versus the previous period, an optional server-rendered sparkline and an `InfoTip`. Replaces `StatCard` and `.kpi`. |
| `Money` | Compact or full rendering, sign colouring, the exact value in a tooltip, and a `currency` prop from `lib/currency.ts`. |
| `Num`, `Pct`, `When` | `When` shows relative time under 24 h, `Oct 25` within the current year, `Oct 25, 2025` otherwise. It renders in local time with UTC in the tooltip, and absorbs `LocalTimestamp`. |
| `EntityLink` | Player (with head), account (name, type badge, id), firm or item. One hover card per entity type, loaded lazily. |
| `PlayerHead` | Served through our own `/api/avatar/[uuid]` proxy, cached for 24 h, so third parties never see visitor IPs. |
| `DataTable`, `FilterBar` | The table and filter-chip system. Specified in §5.5. Replaces every raw `<table>`, the `td.empty` hack, `Toolbar`, `DirectorySort` and the per-page sort headers. |
| `RangePicker` | Presets for 24 h, 7 d, 30 d, 90 d, YTD, 1 y and All, a custom from/to, and a "compare to previous" toggle. URL params: `range`, `from`, `to`, `cmp`. Parsing lives in `lib/range.ts`. |
| `Segmented` | Replaces `WindowTabs` and the four hand-rolled `.window-tabs`, with correct `aria-current` / `aria-pressed`. |
| `Pager` | `Pagination` for numbered pages, `Cursor` for Newer / Older plus jump-to-date. |
| `ExportMenu` | CSV and JSON for "this page" or "all results", the latter through a streaming route (§9.3). |
| `EmptyState`, `Skeleton*`, `ErrorState`, `AccessGate` | `AccessGate` replaces `PrivacyGate` and adds a fourth kind, "request access", which explains which relationship grants access. |
| `SidebarLayout` | For the workspaces (`/me`, `/gov`, `/admin`): left navigation that collapses to a top select on mobile. |
| `HoverCard`, `Popover`, `Dialog`, `CommandPalette` | One accessible popover base. |

### 5.3 Charts

**Decision: keep recharts, fix the bugs, and load it only where it is needed.** A rewrite would have to be prettier, better and faster than recharts at once, which is unlikely. The current charts are buggy, though, and that is what this section fixes.

**One chart module.** `components/charts/AreaTimeline.tsx` today exports eight unrelated charts.
- Split it into one file per chart under `components/charts/`.
- They share a `ChartFrame` (title, legend, "View data", sizing) and shared axis, tooltip and colour helpers in `components/charts/kit.ts`.
- Chart types: `Sparkline`, `AreaChart` / `LineChart` (multi-series, stacked or not), `BarChart` (grouped or stacked), `Donut`, `Waterfall` (opening balance → categories → closing balance), and `Sankey` if money flow needs it.
- The existing server-SVG charts (`Heatmap`, `Candlestick`, `MiniSpark`) stay server-rendered and adopt the same frame.

**Loading.**
- Each recharts chart is imported through `next/dynamic`, so pages without charts never download recharts.
- The server renders a skeleton at the chart's final size, so nothing jumps when it loads.
- The `RC` mount gate that renders an empty placeholder until hydration is replaced by the sized skeleton.

**Bugs to fix**, each with a regression test (component test or Playwright screenshot):
- **Clipped axis labels** (§1.4 item 3). Compute the Y-axis width from the longest formatted tick label instead of a fixed width.
- **Overshoot below zero** (§1.4 item 5). Use `type="monotone"` (or `monotoneX`) on every `Area` and `Line`. Never use `basis` or `natural` curves.
- **Outliers** (§1.4 item 6). When the maximum is more than 10× the 95th percentile, clamp the axis, draw the clipped point at the top with a flag and its value in the tooltip, and offer a "log scale" toggle.
- **Heatmap** (§1.4 item 4). Fill the card width with responsive cell sizes, and show all 24 hours on every breakpoint.
- **Ticks** fall on nice numbers, and money ticks use the compact `Money` format.
- **Dual-axis charts** label both axes with the series colour.
- **Charts left empty for several seconds** before hydration (the `RC` gate) get the sized skeleton above.

**Rules for every chart:**
- A `<details>` "View data" table and an `aria-label` summary.
- Legends in the same place on every chart, and clicking a legend item toggles its series.
- Colours from the `--c-*` / `--cat-*` tokens only, so both themes and both modes work.

### 5.4 CSS clean-up

- Delete the dead CSS listed in §1.5 and the UI audit: the back-compat block, `.infotip`, both `.heatmap` blocks, `.subnav` and `body.compact`.
- Merge the duplicated selectors.
- Move docs-site CSS into `app/docs/docs.css`.
- Target: `explorer.css` ≤ 500 lines and no inline `fontSize`.
- Add a lint rule (`no-restricted-syntax` on JSX `style` props with `fontSize`, `padding` or `margin`) so the clean-up holds.

### 5.5 Tables, search and filter chips

Every table in the app moves to one system. Today sorting, filtering and empty states work differently on almost every page (§1.5), and filter chips exist on only 3 pages.

**Interaction model**

```
┌────────────────────────────────────────────────────────────────────────────┐
│ 🔍 Search accounts…                              [+ Filter]  [Columns] [⤓] │
│ [Type: Business ×] [Balance > $10k ×] [Created: 2026 ×]       Clear all    │
│ 1,204 accounts · sorted by Balance ↓                                       │
├────────────────────────────────────────────────────────────────────────────┤
│ Account ▲▼        Type        Firm           Balance ▼       Created       │
```

- **Search box.** Free text across the table's searchable columns. It also understands `field:value` tokens, so typing `type:business balance:>10000` turns into two chips as the user types. Unknown tokens stay as free text.
- **"+ Filter" menu.** It lists the fields this table can filter on. Choosing one opens a small popover suited to the field type:

  | Field type | Popover | Example chip |
  |---|---|---|
  | Enum | Checkbox list with counts | `Type: Business, Government` |
  | Money or number | Operator (`>`, `<`, between) and value | `Balance > $10k` |
  | Date | Presets plus custom range (shares `RangePicker`) | `Created: 2026` |
  | Entity | Typeahead for player, account, firm or item | `Counterparty: DRB` |
  | Text | Contains or equals | `Reason contains "auction"` |
  | Boolean | Toggle | `Archived` |

- **Chips.** Every active filter is a chip. Clicking a chip reopens its popover to edit it, `×` removes it, and "Clear all" removes them all. Chips are keyboard reachable, and Backspace in an empty search box removes the last chip.
- **Quick filters.** A table may define preset chips that show as suggestions under an empty search, for example "Active fines", "Large (> $10k)" or "This week".
- **Column headers** sort, with `aria-sort`. A second click reverses the order. The sort is shown in the summary line.
- **Columns menu** shows and hides optional columns. The choice is kept per table in `localStorage`.
- **Summary line:** result count (or "1,000+" on unbounded feeds), the sort, and a pending indicator while the server responds.
- **Export (⤓)** exports exactly what the filters select (§9.3).
- **Empty results** say which filters are active and offer to remove the narrowest one.
- **Phones:** chips scroll horizontally, secondary columns collapse into an expandable row, and the filter popovers open as bottom sheets.

**URL format.** All state lives in the URL, so filtered views can be shared and bookmarked.
- `?q=text&f=type:in:business,government&f=balance:gt:10000&sort=-balance`
- One `f` parameter per chip, in the form `field:operator:value`.
- Malformed or unknown filters are dropped and never cause an error.

**One schema per table.** A table is declared once and both sides use the same definition.

```ts
// lib/tables/accounts.ts
export const accountsTable = defineTable({
  id: 'accounts',
  search: ['displayName', 'ownerName', 'accountId'],
  filters: {
    type:     { label: 'Type', kind: 'enum', options: ACCOUNT_TYPES },
    balance:  { label: 'Balance', kind: 'money' },
    created:  { label: 'Created', kind: 'date' },
    firm:     { label: 'Firm', kind: 'entity', entity: 'firm' },
    archived: { label: 'Archived', kind: 'boolean' },
  },
  sorts: ['balance', 'created', 'displayName'],
  defaultSort: '-balance',
  quick: [{ label: 'Businesses', f: ['type:in:business'] }],
});
```

- **`lib/tables/core.ts`** holds `defineTable` and `parseTableState(searchParams, table)`. Parsing is pure, validated with zod, and unit-tested.
- **The service layer** receives the parsed, typed state.
- **The SQL layer** turns it into Kysely `where` / `orderBy` through a per-table mapping. Only declared fields reach SQL, and every value is bound as a parameter.
- **`FilterBar`** (a client component) renders chips and popovers from the same schema, and writes the URL. **`DataTable`** (a server component) renders rows and headers.
- **Enum counts** in the filter popover come from a cached facet query per table, shared through `memo`. They are not computed on every keystroke.
- **Every filterable field must be backed by an index or a rollup.** A filter that would scan a large table is not offered on that table; the PR adds the index (Flyway) or drops the filter.

**`DataTable` details:** server-rendered, column definitions with `align`, `numeric` and `secondary` (hidden under 720 px), sticky header, keyboard-focusable wrapper, footer row of totals where they make sense, a `density` prop, and row links that stay real `<a>` elements.

**Tables that move to this system:**

| Table | Notable filters |
|---|---|
| Accounts directory | Type, balance, created, firm, archived |
| Firms directory | Balance, staff count, HQ, created |
| Players directory (new) | First seen, last seen, has firm |
| Account history, `/me/transactions`, firm transactions, `/gov` transactions, admin transactions | Category, direction, amount, counterparty, date, plugin, initiator |
| Fines | Status, amount, date, issuer, player, account, reason text |
| Market: items, shops, sales feed | Item, price, stock, shop type (player / admin / firm), world, direction |
| Admin: accounts, players, webhooks, API keys, audit | The fields each already has, plus date and actor |

---

## 6. Shell, navigation and information architecture (workstream SHELL)

### 6.1 Top bar

**Left:** logo and server name. **Right:** search (⌘K) and an account menu. Primary navigation:

| Section | Visible to | Contents |
|---|---|---|
| **Economy** | Everyone | Overview, Wealth, Money flow, Taxation |
| **Directory** | Everyone | Players (new), Accounts, Firms |
| **Market** | Everyone | Items, Find shops, Activity, Market firms (orphaned route restored), Sales (permission-gated) |
| **Government** | Viewers whose scope has at least one government account, or with `government.read.all` | Links to `/gov`. Fixes §1.4 item 11. |
| **Investigate** | `transactions.read` | Unchanged |
| **Docs** | Everyone | Unchanged |

**Logged-out account menu:** a "Log in" button.

**Logged-in account menu:**
- A chip with the player's head and **personal balance**.
- Entries: My finances, My transactions, Statements, My market, My firms, Webhooks & API, Link status, Admin (when permitted), Log out.

Logout also ends the Keycloak session (federated `end_session_endpoint`).

### 6.2 Breadcrumbs and next steps

- Every entity page has breadcrumbs, such as Directory › Firms › DRB › Corporate Account.
- Every entity page has a "Related" rail (§7) linking the owner, firm, other accounts and recent counterparties the viewer is allowed to see.
- This is the main tool against the 87% single-page bounce rate.

### 6.3 Page skeleton

Every page uses this order:

`PageHeader` → (optional) `FilterBar` / `RangePicker` → `MetricCard` row → `Section`s

Workspaces use `SidebarLayout`. Public pages are full width up to 1280 px.

### 6.4 Route changes

| New or changed | Notes |
|---|---|
| `/players`, `/players/[name]` | New public player directory and profile (§7.2) |
| `/me/*` | Becomes a `SidebarLayout` workspace (§8) |
| `/gov/*` | New government workspace (§10). `/government` redirects to it. |
| `/admin/audit`, `/admin/gov-units` | New (§12) |
| `/chestshop/firms` | Back in Market navigation as "Market firms" |
| `/design-system` | Linked from `/admin` only |

---

## 7. Public entity pages (workstream ENTITY)

### 7.1 Account page (`/accounts/[id]`): the front door

**Header.** What comes first depends on the account type (§3 rule 5).
- Every account: name, type badge and `#id`. People are shown as an `EntityLink` with their head, never a raw UUID.
- **PERSONAL:** the owner.
- **BUSINESS:** the firm first, as the account holder. The proprietor appears under it as "Proprietor of <firm>", never as the owner of the funds.
- **GOVERNMENT:** its government unit (§10.2), if it has one.
- Actions: copy link and copy `/pay` command. The command syntax comes from config, because DC and SC may differ.

**Public metrics, visible to everyone**
- Balance, with "as of" time.
- Created date, with the year.
- Account type explainer (`InfoTip`).
- For a business account: the firm's size and its number of accounts.

**Public activity totals for GOVERNMENT and BUSINESS accounts** (decided, §14 Q2)
- 30-day money in and money out, transaction count, and a 12-month activity sparkline, all from the rollups.
- Totals only. Individual transactions and counterparties stay private.
- PERSONAL accounts show none of this.

**Logged out, or no access**
- An `AccessGate` that says *why* the history is private and what grants access ("owners, members, and firm finance staff can see this account's history").
- A "Log in to see your own account" button.
- The Related rail stays visible, so the visitor has somewhere to go next.

**With access**
- `RangePicker` (default 30 d) and a balance chart from `balanceSeries`.
- An in/out by category waterfall.
- Activity calendar (365 days, from the rollup).
- Transactions `DataTable`:
  - Cursor pagination.
  - Filters: category, direction, counterparty, amount range, text in the memo.
  - Columns: running balance, and `EntityLink` for the counterparty.
- Counterparties from `explorer_rollup_pair_month`.
- Full export (§9.3).

**Performance target:** at most 3 DB queries for the public view; at most 8 for the full view on a warm cache. Nothing on this page may scan all history.

### 7.2 Player profile (`/players/[name]`): new

A player's page answers "who is this?", not "how rich is this?" (§14 Q1). It is the link target that replaces raw owner UUIDs everywhere.

**Public view**
- Head, name, first seen and last seen. `economy_players` already has these.
- Their PERSONAL account(s) with balance. These are already public on `/accounts/:id`.
- **Firms they are part of, with their role** (proprietor, or employee and role name). Each links to the firm page. **No firm balances are shown on the player page, and nothing is summed.** A firm's money may belong to shareholders or depositors (§3 rule 5).
- Their ChestShop shops, from the public shop data.

**For the player themselves, or a viewer with `player.read.all`:** a link to `/me`, or to the admin player view.

`/players` is a directory that uses the §5.5 table with filters for first seen, last seen and "has a firm". It has no wealth ranking.

### 7.3 Firm page (`/firms/[name]`)

- Same header pattern as the account page.
- Public: accounts and balances, the proprietor (as a role, not an owner of the funds), staff count, HQ, Discord, top items sold (from the market rollups), and 30-day in/out totals across the firm's accounts (§14 Q2).
- Firm internals (members) and financials (finance access) appear as tabs, gated with `AccessGate`.
- **Financials tab:**
  - Revenue and costs by category across all the firm's accounts, from the rollups.
  - Payroll (outflows to employees).
  - Shop performance by item.
  - Monthly P&L statement and export.
- **Fix:** run the permission checks in parallel and dedupe them (§4.3).

### 7.4 Item page and market

- Candles and volume from `explorer_rollup_item_day`, with a `RangePicker`.
- Compare up to 3 items.
- Buy/sell spread over time.
- Keep `ShopOffers`, but render them with `DataTable` and map links.
- `/market` gains a real landing page: top movers (price change over 7 d), most traded, newest listings, and a search box. Today the page is 22 lines.

---

## 8. My finances (`/me`, workstream ME)

`/me` becomes a `SidebarLayout` workspace with these sections: Overview, Transactions, Statements, Taxes & fines, Market, Firms, Developer.

### 8.1 Overview

- **My balance.** The sum of the player's own PERSONAL accounts only, shown with a 90-day chart and the change since the previous period. This fixes §1.4 item 8: business and government money never count towards it (§3 rule 5).
- **Accounts I can access**, in separate groups that are never added together:
  - **Mine** (personal)
  - **Businesses** (by firm, with my role there). Each firm shows its own subtotal, labelled as the firm's money.
  - **Government** (by unit), with each unit's subtotal.
  
  Each row shows the **real relationship**: owner, member, authorizer, viewer, firm finance (role name) or group (LuckPerms group). This also fixes the "member" mislabel.
- **Income and spending this month**, by category (§4.4). Computed over personal accounts only. Transfers between the player's own personal accounts are excluded. Money moving between a personal account and a firm the player works for *is* counted, because it crosses an ownership boundary (wages, dividends, owner draws). Shows the change against last month.
- **Top income sources** and **top spending destinations**, as `EntityLink`s.
- **Recent activity** (10 rows) and a link to Transactions.
- **Nudges:** a "Link your Minecraft account" banner when unlinked. If a business account has a non-zero balance and the player has no finance role there, say why it is shown.

### 8.2 Transactions (`/me/transactions`)

- The full history across every account the player can read.
- An account multi-select that defaults to "my personal accounts".
- The §5.5 table, with chips for category, direction, counterparty, amount, account, memo text and date range.
- Cursor pagination and full export.
- A row opens `/transactions/[id]`, which the player can see because they are a participant.

### 8.3 Statements (`/me/statements`, `/me/statements/[yyyy-mm]`)

A monthly statement per personal account, or for all of the player's personal accounts together. Business and government accounts get statements on their own pages (§7.3, §10.3), never combined with personal ones. Contents:

- Opening balance → inflows by category → outflows by category → closing balance, as a waterfall chart plus a table.
- Largest transactions.
- Tax paid.

Delivery:

- A print stylesheet, so a browser can print it to PDF.
- CSV export.
- The list shows the last 12 months.
- Built entirely from rollups, so a statement costs ≤ 3 queries.

### 8.4 Taxes and fines (`/me/taxes`)

- **Tax paid** by kind and month, from `tax_kind` postings where the player's account pays.
- **Fines received**, from `government_fines.player_uuid_bin`: amount, reason, issuer, date, status (active or revoked), and a link to the fine's transaction.
- **Current tax rates** that apply to the player, from the single source settled in §10.5.

### 8.5 Market (`/me/market`)

- Keep the current content.
- Rebuild it on the rollups and on bounded queries. Today `listPlayerSales` has no LIMIT and is summed in JavaScript.
- Use `Money` for exact totals and `Segmented` / `RangePicker` for the time window.

### 8.6 Firms (`/me/firms`)

- Firms the player owns or works for, with role, permissions summary and hire date (`firm_employment_history`, V39).
- If the player has finance access: each firm's balance and this month's net, labelled as the firm's money and linking to the firm's Financials tab. These figures are never added to the player's own balance.

### 8.7 Developer (`/me/developer`)

- The existing webhooks, moved here.
- A read-only list of the player's API keys and their limits, if the REST API exposes them. Otherwise, instructions for issuing a key in game.

### 8.8 Performance

- The `/me` Overview is ≤ 8 queries and cached per viewer for 30 s.
- Use `memo` with key `me:<uuid>:<range>`, through the bounded L1 cache.

---

## 9. Power tools (workstream POWER)

### 9.1 Search and command palette

**⌘K command palette:**
- Entities: players, accounts, firms, items.
- Pages and actions: "Go to My statements", "Copy server IP", "Toggle theme".
- Recent items, stored in `localStorage`.

**Typeahead:**
- Prefix matching that uses existing indexes: `economy_players.name_lower`, the account `display_name` index (V7), firm names.
- Minimum 2 characters, 200 ms debounce, abort when a newer keystroke arrives.
- Redis-cached for 60 s per `(type, prefix)`.
- Results ranked: exact match, then prefix, then recently active.

**Full search page (`/search`):**
- Adds a contains-match. It runs only when Enter is pressed, never from the typeahead.
- Grouped results with counts and per-type tabs.
- If contains-search is still slow after this, use a MariaDB `FULLTEXT` index on firm and account names (a Flyway migration). Keep player names on prefix matching.

### 9.2 Ranges and comparison

Every time-windowed page uses `RangePicker`, and the range is kept in the URL. With `cmp=1`, `MetricCard` deltas and dashed previous-period series appear on charts.

### 9.3 Full exports

- **Route:** `GET /api/export/[dataset]?…filters`. It streams CSV or NDJSON.
- **Permissions:** the same checks as the page.
- **Row cap:** 100k rows, with a header line saying so when hit.
- **Audit:** every export is recorded as `explorer_audit` action `export`.
- **Rate limit:** per viewer through Redis.
- **Datasets:** account transactions, my transactions, firm transactions, government transactions, fines, sales feed, directory lists, chart series.

### 9.4 Transaction explorer (`/transactions`, `transactions.read`)

- Cursor pagination with no `COUNT(*)`.
- The §5.5 table, with chips for plugin or category, account type on either side, amount range, initiator or authorizer, account, and time range.
- A detail drawer that shows the postings without leaving the list.
- "Follow the money": from a transaction, list the next and previous transactions on the receiving account within N hours.

### 9.5 Small things that compound

| Feature | Notes |
|---|---|
| Copy-link buttons | On every `Section` |
| Keyboard navigation in tables | `j`/`k`, Enter to open |
| Full-state URLs | Including sort, range and filters, so pages can be shared on Discord |
| OpenGraph images | `/accounts/:id`, `/players/:name` and `/firms/:name` get `opengraph-image.tsx` with name, type, server brand, and the balance where the page shows one publicly. Links pasted into Discord are a large share of traffic. Generated only from data that is already public. |

---

## 10. Government workspace (`/gov`, workstream GOV)

### 10.1 Who sees what

The scope is unchanged. RBAC and ReBAC stay separate.

- **RBAC.** The `government.read` permission opens the workspace, and `government.read.all` gives every government account.
- **ReBAC.** The **scope** is the set of GOVERNMENT accounts the viewer can read through `account_read_access_web`. In practice that comes from LuckPerms group access, for example the Department of Commerce group.
- Every number in `/gov` is computed only over accounts in scope.
- Relationship-based reads in `/gov` are written to `explorer_audit`, which closes the gap in §1.6.

### 10.2 Government units (presentation grouping)

**Data:**
- New table `explorer_gov_unit(unit_id, slug, name, parent_unit_id NULL, description, created_at, created_by)`.
- New table `explorer_gov_unit_account(unit_id, account_id)`.
- The hierarchy allows nation → department, or state → city on StateCraft.

**Who manages them:** admins, at `/admin/gov-units`, with a new `gov_units.manage` permission.

**Visibility:** a unit is visible to a viewer if the viewer can read at least one of its accounts. Its totals include only the accounts the viewer can read. If the viewer cannot read all of them, the page shows "partial view: 3 of 5 accounts".

**Unassigned accounts:** government accounts in scope that belong to no unit appear under "Unassigned".

**Ledger totals are shown as they are.** Treasury totals include every in-scope GOVERNMENT account, minting accounts such as `TreasuryIngest` included, with their negative balances (§3 rule 8). Units let officials see a department's own accounts separately, so the mint does not need to be hidden to make a department's figures readable.

### 10.3 Pages

All pages share the unit selector (All units, or one unit) and `RangePicker`. Every list is a §5.5 table with filter chips. All reports and views are internal to `/gov`; nothing here is published publicly (§14 Q3).

**Overview**
- Treasury total, net flow for the period, revenue and spending as a monthly bar chart, and balance over time.
- Largest movements in the period.
- Unit cards showing balance and net.

**Accounts**
- Every in-scope account with balance, 30-day net, a sparkline and its unit.
- Links to the account page.

**Revenue**
- By `tax_kind` (personal, corporate, income, sales, property), fines, and other inflows by category.
- Broken down by unit.
- The highest-paying players and firms into in-scope accounts, for taxes and fines (§14 Q4), with links to their pages.

**Spending**
- Outflows by category and by recipient type: players, firms, other government units (internal, shown separately), system.
- The largest recipients.
- **Spending by official**, using `ledger_txns.initiator_uuid_bin` and `authorizer_uuid_bin`. The ledger already records who initiated and approved each transfer.

**Payroll**
- `treasury-salary` outflows: recipients, totals per period and run history.
- Detects missed or duplicated runs, using the rollup run cadence.

**Fines**
- **The "Fine categories" grouping is removed.** Reasons are free text written by players, so first-word grouping produced junk such as `c027-c028` and `.` (§1.4 item 2). No classifier replaces it.
- Instead, the fines list becomes a §5.5 table:
  - search across the reason text
  - chips for status (active / revoked), amount, date, issuing official, fined player and receiving account
  - quick filters such as "Active" and "This week"
  - full export
- Summary metrics above the table: fines issued, total amount, revocation rate, and a top-issuers list, all for the current filters.

**Reports**
- A monthly report per unit: opening and closing balance, revenue and spending by category, top movements, fines summary.
- Print-to-PDF stylesheet and CSV.
- Internal only. There is no publishing to a public page (§14 Q3).

**Audit**
- Ledger-side: who moved money in or out of in-scope accounts (initiator and authorizer).
- Explorer-side: who viewed or exported in-scope data, from `explorer_audit` filtered to in-scope targets.
- Requires `government.audit.read`.

### 10.4 Compliance tools (Department of Commerce style)

**Access goes through the existing chain, with nothing hard-wired** (§14 Q7):

LuckPerms group → synced explorer group → role → permissions (RBAC), combined with relationships where a check is per-resource (ReBAC).

- The tools are gated by permission keys: `firm.financials.read.all` together with `transactions.read`.
- A `compliance` role definition containing those keys is seeded by migration so it is ready to use. It is attached to nobody.
- An admin gives a department the tools by linking its explorer group (for example the one bound to `treasury.group.doc`) to the `compliance` role in `/admin/groups`. Membership then follows LuckPerms automatically.
- No code refers to a specific group, department or server.

**Tools**
- **Firm investigation view** at `/gov/compliance/firms/[id]`:
  - all of the firm's accounts
  - money in and out by counterparty
  - transfers to and from the owner's personal accounts
  - large or round-number transactions
  - sudden balance changes against the firm's 90-day baseline
- **Watchlist:** `explorer_watchlist(viewer_uuid, account_id)`. Watched accounts appear on the compliance home page with recent activity. Optional webhook alerts reuse the existing webhook infrastructure.

### 10.5 One source for tax rates

The taxation page reads REST `/api/v1/config/tax`, but the docs read `economy_config`. Pick **one**: the REST endpoint, cached for 60 s, with `economy_config` as fallback only. Publish corporate and ChestShop rates too.

### 10.6 New permission keys and roles

All new keys go into `lib/auth/policy.ts`, and a Flyway migration seeds the role bundles. Like every other permission, they are granted through roles, directly or through groups (including LuckPerms-synced groups).

| Key | Gates | Seeded into |
|---|---|---|
| `government.audit.read` | The Audit tab, within scope | `government`, `admin` |
| `gov_units.manage` | `/admin/gov-units` | `admin` |
| `audit.read` | `/admin/audit`, with full scope | `admin` |

| New role | Permissions | Attached to |
|---|---|---|
| `compliance` | `firm.financials.read`, `firm.financials.read.all`, `transactions.read`, `market.sales.read` | Nobody. Admins link it to a group (§10.4). |

---

## 11. Performance budgets and verification

| Surface | Budget | How it is checked |
|---|---|---|
| Any page, cold pod or cold cache | First byte ≤ 2 s and never an error | A Playwright test against the dev server after `redis FLUSHDB` on the explorer namespace |
| `/accounts/:id` public | ≤ 3 queries, p95 ≤ 150 ms | Server-Timing, Prometheus |
| `/accounts/:id` with access | ≤ 8 queries, p95 ≤ 400 ms | Same |
| `/me` Overview | ≤ 8 queries, p95 ≤ 400 ms | Same |
| `/gov` Overview | ≤ 10 queries, p95 ≤ 600 ms | Same |
| JS for first paint on `/`, excluding the deferred recharts chunk | ≤ 170 KiB compressed | A CI script comparing `.next` build output against a committed budget file |
| JS for first paint on routes without charts | ≤ 170 KiB compressed, and no recharts chunk at all | Same |
| Charts | Sized skeleton in the server HTML; no layout shift when the chart loads | Playwright CLS check on `/`, `/accounts/:id` and an item page |
| LCP p75 / INP p75 | ≤ 1.8 s / ≤ 200 ms | Faro, reviewed two weeks after release |
| Request-path SQL | No query in the request path scans more than one account's history or more than 31 days of raw ledger | Code review checklist, plus `EXPLAIN` for new queries in the PR description |

---

## 12. Admin and audit (workstream ADMIN)

**`/admin/audit`**
- A §5.5 table with chips for actor, action, target type and id, and time range.
- Export.
- Requires `audit.read`.
- Add before and after values for mutations (a JSON column, added by migration).
- Record relationship-based reads of GOVERNMENT and BUSINESS accounts.
- Keep the log fail-open, but count failures in metrics.

**Other admin pages**
- `/admin/gov-units` (§10.2).
- `/admin/groups` gains a clear way to link a group to a role such as `compliance` (§10.4), showing the LuckPerms node each group syncs from.
- Add `/admin/role-grants` to `AdminNav`.
- A management UI for group sync sources (`explorer_group_sync`), which today are configured directly in the database.

**Attributing writes to people (cross-service)**
- The explorer sends `X-Acting-Player: <uuid>` with every admin call to treasury-rest-api.
- treasury-rest-api records that header on the transaction it creates: as initiator, or as a memo or audit row.
- This needs a matching treasury-rest-api change. It stays a separate PR in this repo that the admin workstream depends on.

**Clean-up**
- Delete `requireAdmin`, `requireRole`, `findCapabilities`, `findRoles`, `SubNav`, `ComingSoon`, `Sparkline` and `TopAccountsBar`.
- Wire up `MoneyFlowDiagram` in the money-flow redesign, or delete it.
- Fix the viewer fail-soft path (§1.4 item 10) so it falls back to the player baseline.

---

## 13. Delivery plan for parallel agents

### 13.1 Waves

```
Wave 0 (parallel, foundations, each a small PR)
  D0     Instrumentation + perf baseline                     (§4.1)
  D1     Rollup schema + in-process worker + backfill        (§4.2)   ← needs Flyway number
  DS     Tokens v2 + primitives + chart fixes/frame + gallery (§5.1–5.4)
  TABLE  DataTable + FilterBar chips + defineTable/URL state (§5.5), proven on the accounts directory
  Q      Quick-fix defects that don't depend on redesign (§1.4 items 1, 2, 9, 10, 11, 12;
         §4.3 cache hardening + request dedupe)

  ── user review gate: /design-system gallery screenshots in both themes, the accounts
     directory on the new table, and the perf baseline ──

Wave 1 (parallel, each consumes Wave 0 contracts; each moves its own tables to §5.5)
  SHELL   Top bar, nav, account menu, breadcrumbs, ⌘K shell, layout viewer hydration (§6, §9.1 UI)
  ENTITY  Account page, player profile, firm page, item/market pages (§7)
  ME      /me workspace (§8)
  GOV     /gov workspace, units, fines table, compliance tools (§10)
  POWER   Search backend, exports API, transaction explorer, range/compare plumbing (§9)
  ECON    Overview, wealth, money flow, taxation rebuilt on rollups + fixed charts
  ADMIN   Audit UI, gov-unit admin, group→role linking, cleanup, X-Acting-Player (§12)

Wave 2 (serial)
  VERIFY  Budgets (§11), a11y sweep across new routes, docs (economy-explorer/docs + wiki),
          remove dead CSS/components, Umami goals for single-page bounce
```

**Tesks tickets.** The epic is PAR-530. Every commit cites its workstream's ticket.

| Workstream | Ticket | Workstream | Ticket |
|---|---|---|---|
| D0 | PAR-531 | SHELL | PAR-536 |
| D1 | PAR-532 | ENTITY | PAR-537 |
| DS | PAR-533 | ME | PAR-538 |
| TABLE | PAR-534 | GOV | PAR-539 |
| Q | PAR-535 | POWER | PAR-540 |
| | | ECON | PAR-541 |
| | | ADMIN | PAR-542 |
| | | VERIFY | PAR-543 |

### 13.2 Contracts that Wave 0 must publish

Wave 1 workstreams start only after these exist on `develop`.

| Contract | Location | Owner |
|---|---|---|
| Money arithmetic and formatting | `lib/money.ts` | Q |
| Range parsing and URL params | `lib/range.ts` | DS |
| Category taxonomy | `lib/categories.ts` | D1 |
| Rollup read helpers: `accountDaySeries`, `balanceSeries`, `categoryTotals`, `counterparties`, `economyDays`, `itemDays` | `lib/sql/rollup.ts`, `lib/services/rollup.ts` | D1 |
| Cursor encode and decode | `lib/pagination/cursor.ts` | Q |
| Primitives, `ChartFrame` and chart helpers | `@democracycraft/design-system` exports, `components/charts/kit.ts`, and the gallery | DS |
| Table schema, URL state, `DataTable`, `FilterBar` | `lib/tables/core.ts`, `packages/design-system/src/table/` | TABLE |
| Metrics helpers | `lib/metrics.ts` | D0 |

### 13.3 File ownership (to avoid merge conflicts)

| Workstream | Owns | Must not edit |
|---|---|---|
| D0 | `instrumentation.ts`, `lib/metrics.ts`, `app/metrics/`, `ops/perf-baseline.md`, `ops/grafana/`. D0 also creates a no-op `lib/rollup/start.ts` and calls it from `instrumentation.ts`, so D1 never has to edit that file. | Pages |
| D1 | `economy-flyway` rollup migration, `lib/rollup/**`, `lib/sql/rollup.ts`, `lib/services/rollup.ts`, `lib/categories.ts`, rollup status in `app/healthz/` | Pages, `lib/sql/ledger.ts`, `instrumentation.ts` |
| DS | `packages/design-system/**` except `src/table/`, `components/charts/**`, `app/design-system/`, `lib/range.ts`, lint rule | `app/**` pages |
| TABLE | `packages/design-system/src/table/**`, `lib/tables/**`, `components/FilterBar*`, and the accounts directory (`app/accounts/page.tsx`, `buildAccountsWhere`) as the proving page | Other pages |
| Q | `lib/money.ts`, `lib/cache.ts`, `lib/pagination/`, `lib/auth/viewer.ts` fail-soft, `lib/auth/access.ts` dedupe, targeted one-line page fixes | New pages |
| SHELL | `app/layout.tsx`, `components/Header*`, `components/Footer.tsx`, `components/ViewerContext.tsx`, `components/CommandPalette*`, `proxy.ts` | Page bodies |
| ENTITY | `app/accounts/**` (except the directory, after TABLE), `app/players/**`, `app/firms/**`, `app/chestshop/**`, `app/market/**`, the matching `lib/sql`/`lib/services` files | `/me`, `/gov` |
| ME | `app/me/**`, `lib/sql/me.ts`, `lib/services/me*.ts`, `components/PlayerDashboard.tsx` (replaced) | |
| GOV | `app/gov/**`, `app/government/` (redirect), `lib/sql/government.ts`, `lib/services/government*.ts`, gov unit and `compliance` role migrations | |
| POWER | `app/api/export/**`, `app/search/`, `lib/sql/search.ts`, `lib/actions/search.ts`, `app/transactions/**` | |
| ECON | `app/page.tsx`, `app/economy/**`, `app/money-flow/`, `lib/sql/{stats,health,moneyFlow,taxation,bridgeAnalytics}.ts`, `lib/services/economy.ts` | |
| ADMIN | `app/admin/**`, `lib/audit.ts`, `lib/sql/audit.ts`, `lib/actions/admin*.ts`, `lib/treasury.ts`, treasury-rest-api header support | |

**Shared files.** Changes to `lib/auth/policy.ts` and to Flyway numbering are serialised: rebase and renumber at merge time.

### 13.4 Definition of done (every PR)

- **Tests:**
  - Unit tests in `test/unit`.
  - SQL integration tests in `test/integration` against the test database, for every new query.
  - Component tests for new primitives.
  - Playwright e2e and axe tests for every new route, in both themes.
- **Verification:**
  - Run on the dev server (economy.dev.paradaux.io, a production clone).
  - Screenshots in the PR for UI changes.
  - Before and after numbers for performance changes, against `ops/perf-baseline.md`.
- **Docs:** the user guide is updated for player-visible changes.
- **Commits:** the Tesks `PAR-…` ref is in every commit subject, and there are no co-author trailers.

---

## 14. Decisions

Recorded 2026-10-26.

| # | Question | Decision | Where it applies |
|---|---|---|---|
| — | Is the −$157M "Gov treasury" a bug? | No. A pure ledger works this way: the account that minted money holds the negative balance. Show it as it is. | §1.4, §3 rule 8, §10.2 |
| — | Fine categories (first word of a player-written reason)? | Remove them. | §1.4 item 2, §10.3 Fines |
| — | `/me` adds personal, firm and government money together? | Fix it. | §8.1 |
| Q1 | Should a player profile aggregate a player's accounts and balances? | No. A business is not solely its owner's: firms can have shareholders, and the biggest firms are banks holding deposits. Profiles show personal accounts and firm roles, never firm balances or totals. | §3 rule 5, §7.1, §7.2, §8 |
| Q2 | Public activity totals on GOVERNMENT and BUSINESS account pages? | Yes. | §3 rule 9, §7.1, §7.3 |
| Q3 | Publish government reports to a public page? | No. Reports stay internal to `/gov`. | §10.3 |
| Q4 | Can officials see which players paid the most tax or fines into their treasury? | Yes. `/gov` Revenue lists the top paying players and firms for in-scope accounts. | §10.3 Revenue |
| Q5 | Editable fine categories? | Moot: fine categories are removed. | — |
| Q6 | Government budgets? | No. | §15 |
| Q7 | Compliance access for the Department of Commerce? | Through LuckPerms group sync + RBAC/ReBAC. A seeded `compliance` role that admins link to a group; nothing hard-wired. | §3 rule 4, §10.4, §10.6 |
| Q8 | Where does the rollup worker run? | Everything runs from the plugins, the REST API or the explorer itself. The worker runs in the explorer process with a Redis leader lease. | §3 rule 2, §4.2 |
| Q9 | Replace recharts? | No. Keep recharts, fix the chart bugs, and load it only where needed. | §5.3 |
| Q10 | Release `develop` before the redesign? | No. The redesign is the last polish before `develop` is published. | Header, §11 |
| — | Tables | Rework every table around search and filter chips. | §5.5 |

---

## 15. Out of scope

- Any change to how money moves, or new in-game commands.
  - The one exception is the `X-Acting-Player` attribution header in §12.
- A native mobile app, and push notifications. Webhooks stay the notification channel.
- Replacing Keycloak, or changing how Minecraft accounts are linked.
- A public REST API for the explorer's rollups. Revisit after the rollups have been stable for a while.
- Government budgets (Q6), publishing government reports publicly (Q3), and any public "public finances" page.
- Hiding, excluding or re-labelling minting accounts in totals.
- Replacing recharts (Q9).
- Any new deployable: CronJobs, sidecars or standalone workers (Q8).
- Wealth rankings of players.
