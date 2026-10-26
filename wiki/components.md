# Components

Every project in the monorepo, what it's responsible for, and how it's put
together. For how they fit as a whole, see [architecture.md](architecture.md).

## JVM build topology

All JVM projects are part of **one** Gradle build (`settings.gradle.kts` at the
root includes them all). Group is `io.paradaux`, Java toolchain is 25, plugin
versions are centralized in the root `build.gradle.kts`. Cross-project
dependencies are wired with `project(...)` deps, so nothing resolves a sibling
from a published snapshot. See [build-and-test.md](build-and-test.md).

---

## `treasury/` — the ledger (Paper plugin)

The system of record. Owns the double-entry ledger, account lifecycle,
government accounts and fines, and tax.

- **Stack:** Paper API · Guice (DI) · MyBatis (annotation mappers) · HikariCP ·
  MariaDB · `hibernia-framework` (commands/config/i18n). Shaded into one plugin
  jar (Shadow), with bundled libs relocated under `io.paradaux.libs.*`.
- **Layering:** `commands/` & `events/` (thin entrypoints) → `services/` +
  `services/impl/` (all logic & transactions) → `mappers/` (SQL only). DI modules
  in `guice/`.
- **Key services:** `LedgerService` (all money movement — `transfer`, admin
  give/take/set, history), `AccountService` (CRUD, non-locking balance reads),
  `MembershipService` (members/authorizers), `GovService`, `BalanceTaxService`,
  `TaxApiImpl` + `TaxCycleRegistry`.
- **Balances** are maintained by **database triggers** into a materialized table,
  not by Java — so the plugin and the REST API can both post safely.
- **Vault** integration is optional (only wired if Vault is on the classpath).

### `treasury/treasury-api/` — public Treasury API (library jar)

The stable surface downstream code compiles against: `TreasuryApi`, `TaxApi`, the
`TaxCycleEvent`, and all model POJOs (`Account`, `LedgerTxn`, `TransferRequest`,
`AccountType`, `Page<T>`, …). Published as `io.paradaux:treasury-api`. **New public
types go here, never in the plugin root.**

---

## `business/` — companies (Paper plugin)

Firms/companies: creation, staff with custom roles and permissions, firm accounts,
sales, and per-firm tax — all money via `TreasuryApi`.

Firm creation takes a per-proprietor row lock in `firm_creation_lock` before any
validation queries and holds it through transaction commit/rollback. Concurrent
creates for the same owner (including across servers) therefore re-check the
ownership limit and cooldown after the previous create finishes. Different owners
can create concurrently; firm-name uniqueness remains enforced by the existing
unique index. Lock rows are retained after disband and must not be pruned while
writers are active. Deploy Flyway V34 before the updated Business plugin.

Firm creation is **not crash-safe across Business and Treasury** (ADT-92).
The firm, default roles/permissions, account link, and default-account update
share one Business transaction. Roles/permissions are written before Treasury
creation to reduce the orphan window, but Treasury commits independently. A
crash after Treasury commits its account and before Business commits can leave
an active BUSINESS account (possibly with owner membership/authorization) with
no firm or account link. Runtime exceptions inside the guarded creation block
attempt to archive the account; process death and a later transaction-commit
failure bypass that compensation. An archive failure is logged as requiring
manual cleanup.

The membership sweep below only visits committed links and **cannot repair these
creation orphans**. After an interrupted create, an operator must investigate
Treasury BUSINESS accounts against persisted Business links while firm creation
is quiescent across servers. An absent link during a running create is not proof
of an orphan. Verify ownership and balance before cleanup through Treasury's
supported operations; preserve funds and ledger history. Retrying creation does
not discover or re-link the earlier account. Durable provisioning/retry support
or a dedicated orphan reconciliation API remains future work.

- **Stack:** same shape as Treasury (Paper · Guice · MyBatis · HikariCP ·
  `hibernia-framework`). Libs relocated under `io.paradaux.business.libs.*`.
- **Layering:** `commands/` → `services/`(+`impl/`) → `mappers/`.
- **Depends on** `treasury-api` (compile/runtime) to move money, and registers its
  own API as a Bukkit service.
- **PlaceholderAPI** is optional. The bundled `business` expansion resolves
  `%business_firm_balance_<name>%` through `BusinessApi.firms()` and
  `FirmApi.getTotalBalance`, without direct mapper access. It persists across PAPI
  reloads and unregisters on disable. `:business:testWithoutPlaceholderApi` checks
  startup wiring with the optional API removed from the runtime classpath.

Firm-account membership is derived from the proprietor and current staff roles;
**Treasury is the access authority**. Business reconciles through Treasury's API,
whose individual mutations commit independently. The per-firm lock serialises
reconciliation only within one Business JVM; it provides no cross-process lock or
cross-plugin transaction.

`ReconcileAccountMembersJob` repairs membership asynchronously at startup and
every 1,200 ticks (about one minute). Each pass uses persisted live firm-account
links as its recovery worklist and re-reads roles and Treasury membership. This
full sweep deliberately replaces an exception-time `needs-resync` marker: it also
covers crashes before a marker could be saved, and Business transaction rollbacks
after Treasury has committed. A failed account is logged with its firm/account IDs
and does not block other accounts; it remains eligible on subsequent passes and
after restarts. Removed links and archived firms are skipped. Already-correct
membership produces no writes. Sweep cost scales with linked accounts and staff;
overlapping scheduled passes are skipped.

Recovery is eventual: stale access can persist until a successful pass, longer
during an outage. Immediate command-triggered sync still reports failures to the
caller. No Treasury API or schema upgrade is required for this repair.

### `business/business-api/` — public Business API (library jar)

`FirmApi`, `StaffApi`, `RolePermission`, and firm model types. Published as
`io.paradaux:business-api`. Consumed by ChestShop (and anything else that needs to
route money to a firm or check a staff permission).

Account resolution (PAR-14) stays with the API that owns the relationship:

| Lookup | API |
|---|---|
| Player UUID → personal account ID | `treasury.getAccountByUUID(uuid)`, then `getAccountId()` (null if absent) |
| Player UUID → accounts by type | `treasury.getAccountsByTypeAndOwner(AccountType.BUSINESS, uuid)` |
| Account ID → player owner UUID | `treasury.getAccountById(id)`, then `getOwnerUuid()` (check for null account) |
| Firm ID → all linked account IDs | `business.firms().getAccountIds(firmId)` |
| Firm ID → configured default account ID | `business.firms().getDefaultAccountId(firmId)` |
| Account ID → firm ID / firm | `business.firms().getFirmIdByAccountId(id)` / `getFirmByAccountId(id)` |

Firm lookups exclude removed links but include archived firms' remaining links
(e.g. an incomplete disband). The list is empty for unknown/unlinked firms; singular
lookups return null when absent. Default lookup does not select a fallback account.
These are relationship reads, not permission or Treasury account-status checks.
A proprietor can own several firms, so filtering Treasury accounts by owner is not
a substitute for firm lookup. None of these reads creates an account; use
`resolveOrCreatePersonal` only when creation and starting-balance funding are intended.
Upgrade the Business provider with the API: older implementations remain compatible,
but the default `getAccountIds` implementation throws `UnsupportedOperationException`
rather than silently reporting an incomplete list. No migration is needed.

`FirmApi.resolveShopAlias(alias)` returns the Treasury account ID for a live firm's
shop alias, or null. Business owns alias persistence and proprietor-only management;
ChestShop resolves `A:alias` through this API, checks normal shop access, and keeps
that alias as the account's sign name. Its synthetic UUID still encodes the Treasury
account ID for payments. Aliases contain 1–12 ASCII letters only. Changing or clearing
an alias stops old signs from resolving; `firm_shop_alias_reservations` permanently
reserves each used name to its original account, so old signs cannot be redirected.
Reservation and current-alias updates share a transaction. Aliases are case-insensitive;
archived firms/accounts and removed links do not resolve. Apply migration
`V35__business_shop_aliases.sql` before deploying the updated Business and ChestShop
plugins together.

---

## `treasury-api-plugin/` — API-key issuer (Paper plugin)

Issues and manages the **JWT API keys** that authenticate callers of the REST API.

> ⚠️ This is **not** the `treasury-api` jar. The jar is the in-process Java API;
> this plugin mints HTTP credentials. Different things, similar names.

- **Stack:** Paper · Guice · MyBatis · `hibernia-framework`. Shadow jar with libs
  relocated under `io.paradaux.treasuryapi.libs.*`.

---

## `treasury-rest-api/` — REST service (Spring Boot)

A REST/HTTP surface over the ledger for tooling and integrations.

- **Stack:** Spring Boot · MyBatis (Spring Boot starter, annotation mappers) ·
  Bucket4j (rate limiting, Caffeine/Redis) · MariaDB. Built as a Spring Boot
  `bootJar` and shipped as a **Docker image** (Harbor registry, deployed via Argo
  CD). DI is Spring (constructor injection).
- **Layering:** `controller/` (DTOs, no logic) → `service/` (`@Transactional`) →
  `mapper/`. Auth, rate-limiting, and config live in their own packages
  (`security/`, `ratelimit/`, `config/`).
- Writes to the **same** ledger tables as the Treasury plugin — concurrency safety
  comes from the database owning balance maintenance.

---

## `economy-flyway/` — the shared schema (Flyway)

The **authoritative** database schema for the whole economy. Every schema change is
a versioned `V<n>__*.sql` migration here. Deploys run through gated, manual
workflows (info → migrate, plus a staging-clean for dry runs). See
[database.md](database.md). Ships only `mysql-connector-j` on the Flyway classpath
(wire-compatible with MariaDB).

---

## `chestshop/` — sign shops (Paper plugin, vendored fork)

A fork of **ChestShop-3** that turns signs into automated shops, integrated with
Treasury and Business so a sale can pay a player or a firm.

- **Vendored fork:** DC-specific changes are expected; it's built here, not pulled
  from upstream.
- **Single Gradle module** (`plugin/`) compiled against the **Paper 26.3** API,
  shaded straight to **`chestshop-<version>.jar`**. (Originally ported from Maven with the
  upstream multi-version adapter matrix — a 1.13.2-baseline core + seven
  `Spigot_*`/`Paper_*` adapter modules + an `assemble` module — but since DC runs
  one modern version, those were folded into the core and the extra modules removed.)
- **Vendored deps:** a Maven-layout `repo/` holds plugin jars not available from
  any public repository (committed to the repo).
- Compiles against `treasury-api` and `business-api` (resolved as local projects in
  the build). See [build-and-test.md](build-and-test.md) for the shading details.

---

## `economy-explorer/` — explorer + user docs (Next.js)

The public, **read-only** web surface and the player/admin documentation site.

- **Stack:** Next.js (App Router) · npm · MySQL (read) · Redis. Dockerized;
  multi-tenant by config.
- **Layering:** Server Component pages (`app/**/page.tsx`) for reads → `lib/sql/*`
  (Kysely query functions, pure DAL). Route handlers exist only for auth, health and metrics.
- **Docs:** the user guide lives in `economy-explorer/docs/` (Markdown) and is
  served at **`/docs`** by `app/docs/[[...slug]]/page.tsx`. This is the canonical
  player/admin documentation.
- The **only** non-JVM project; it stays on its own npm toolchain rather than being
  forced into Gradle.

---

## `realty/` — property *(git submodule)*

Land and property: buying, renting, offers, and auctions. Maintained in its own
upstream repository (the DemocracyCraft changes were merged upstream) and consumed
here. Not part of the root Gradle build; integrates via the economy APIs at runtime.

## `hibernia-framework/` — the framework *(git submodule)*

The shared foundation the Paper plugins are built on: Brigadier-based annotation
commands (`@Command`), type-safe configuration (`@ConfigurationComponent` +
`ConfigurationLoader`), and i18n (`Message`, MiniMessage). Published as
`io.paradaux:hibernia-framework`; lives in its own repository.

---

### A note on the submodules

`realty/` and `hibernia-framework/` are **git submodules**. They are absent from a
plain checkout until initialised, and the root build consumes them as published
`io.paradaux:*` artifacts rather than building them from source. If you need them
locally:

```bash
git submodule update --init --recursive
```

### Taxation dashboard

`/economy/taxation` is a public SSR aggregate view (PAR-92). The service caches
7/30-day results with the explorer's 60-second SWR cache. `lib/sql/taxation.ts`
counts government posting credits using the actual writer plugin/message pairs:
`treasury` + `Personal balance tax (` / `Income tax on deposit from `,
`business` + `Weekly Corporate Balance Tax`, and `ChestShop` + `ChestShop sales tax (`.
The Tax API's `taxType` is not stored in the ledger. Fine collections and refunds
are linked through `government_fines.txn_id` and `revoke_txn_id`; each posting is
counted once even with duplicate historical fine records. Refunds belong to their
settlement period. The public result has no payer IDs, transaction IDs or reasons.

Live rates use the public PAR-80 REST contract at `/api/v1/config/tax`, via
`TREASURY_API_BASE_URL` (with or without its `/api/v1` suffix). No admin token is
sent. Rate reads have a three-second timeout and a separate 60-second SWR cache;
an absent endpoint, invalid section or failed request renders that section as
unavailable without breaking ledger aggregates. No migration or PAR-80 merge is
required for the dashboard; published rates appear when that endpoint is deployed.

### Treasury reload concurrency

The injected salary, balance-tax and source-income-tax configuration objects are
stable holders of immutable `Snapshot` records. Parse with `prepareReload` before
publishing; retain `snapshot()` once per operation when reading multiple fields.
`ConfigReloadServiceImpl` validates YAML and prepares all three candidates before
publishing any. Invalid manual settings leave all three previous snapshots intact.
The salary interval remains pinned to the startup timer. `SalaryService.Payroll`
carries the funding account alongside the payment plan across the async boundary.
Tax destination caches include the account name so a reload cannot reuse an ID
resolved for a different configured account.

The annotation-driven framework components still use the existing in-place reload
for Guice compatibility; their fields are volatile for visibility. They do not
provide a multi-field snapshot or a transaction spanning messages, logging and all
configuration components. The snapshot guarantee above is per manual configuration.

### Explorer timestamps (PAR-40)

Render event instants with `components/LocalTimestamp.tsx` in both server and
client components. It uses a UTC server/hydration snapshot, then detects the
browser's IANA timezone without a cookie or request-specific layout. Pass a
`Date` or ISO timestamp with an explicit offset. `fmtTs` defaults to UTC and
accepts an explicit timezone for pure formatting.

Daily SQL aggregates remain UTC buckets: `fmtDate` and activity calendar date
arithmetic must stay in UTC, rather than shifting already aggregated dates
into the browser's zone. Daily charts label this distinction.

### Explorer money, cache and request dedupe (PAR-535)

- **Money is exact.** `lib/money.ts` parses DB decimal strings to integer cents
  (`bigint`), sums them, and formats them; `fmtAmt`/`fmtAmtFull` delegate to it.
  Never add amounts with `parseFloat` or `Number`. `toNumber` and `ratio` exist
  only for chart coordinates and percentages.
- **`memo(key, windowMs, producer, { maxStaleMs, lockMs })`** in `lib/cache.ts`
  is stale-while-revalidate with a bounded L1 (5,000 entries, about 64 MiB).
  A value older than `maxStaleMs` (default 60 windows) is recomputed before it
  is served, falling back to the old value only if that recompute fails. Cold
  misses take a Redis lock: one pod computes and the others poll for its result
  until the lock TTL (default 20 s) ends. Failures are never stored.
- **Request dedupe.** `lib/auth/access.ts` exports React `cache()` wrappers of
  `findAccount`, `canReadAccount`, `findFirmByDisplayName`, `findFirmById`,
  `isFirmMember` and `hasFirmFinancialAccess`. Pages import those rather than
  the raw `lib/sql` functions. Check many accounts at once with
  `readableAccountIds(viewer, ids)`, not a `canViewAccount` loop.
- **Keyset cursors.** `lib/pagination/cursor.ts` encodes `(timestamp, id)` as
  an opaque base64url token for Newer / Older pagination on unbounded feeds.

### Explorer chrome: navigation, account menu, palette (PAR-536)

- **One nav model.** `lib/nav.ts` declares the six top-bar sections (Economy,
  Directory, Market, Government, Investigate, Docs), their pages and their
  visibility rules. The top bar, the section nav under it, the phone menu, the
  command palette and `Breadcrumbs` all read it. The chrome infers the active
  section from the path, so pages never declare it and never render section
  tabs (`SectionTabs` is a deprecated no-op). A new page under an existing
  section prefix is highlighted automatically; a new page that should appear in
  the section nav is one entry in `NAV_SECTIONS`.
- **Viewer on the server.** The root layout awaits the React-cached
  `getViewer()` and passes `lib/services/nav.ts` `chromeViewer()` (a
  `lib/viewerSnapshot.ts` `ViewerCtx`) to `ViewerProvider`; `useViewer()`
  reads it. `/api/viewer` returns the same snapshot and is used only by
  `refreshViewer()` (back/forward-cache restores). The Government entry needs
  `government.read.all`, or `government.read` plus a GOVERNMENT account in the
  viewer's ReBAC scope (`lib/sql/nav.ts`, memoised per player for 60 s). The
  account chip's balance is the sum of the PERSONAL accounts the player owns,
  memoised for 30 s and streamed behind Suspense. Both are presentation only.
- **Breadcrumbs.** `components/Breadcrumbs.tsx` is a server component. Put it
  first in an entity page, above `PageHeader`:
  `<Breadcrumbs section="/firms" items={[{ label: firm.name, href }, { label: account.name }]} />`.
  `section` supplies the leading crumbs from `lib/nav` (Directory › Firms).
- **Command palette.** `components/CommandPalette.tsx`, mounted once by the
  layout, opened by ⌘K / Ctrl+K, `/`, or `openPalette()`. Entities come from the
  `searchPreview` server action (200 ms debounce, two-character minimum, stale
  results discarded through an `AbortSignal`); pages and actions from
  `components/CommandPaletteEntries.ts`; recents from `localStorage`.
- **Logout** ends the Keycloak session too: `logoutAction` clears the Auth.js
  cookie, then redirects to the IdP's `end_session_endpoint` with `client_id`
  and `post_logout_redirect_uri` (`lib/auth/endSession.ts`). No ID token is
  stored, so Keycloak asks the user to confirm.

### Explorer metrics and Server-Timing (PAR-531)

`instrumentation.ts` sets up Prometheus metrics (`lib/metrics.ts`) in the Node
runtime. It also starts the rollup worker through `lib/rollup/start.ts`.
`/metrics` serves them only with `Authorization: Bearer $METRICS_TOKEN`; without
the token, or with no token set, it returns 404. The Grafana dashboard is
`economy-explorer/ops/grafana/economy-explorer.json`, and the measured
baseline is `economy-explorer/ops/perf-baseline.md`.

| Metric | Labels | Source |
|---|---|---|
| `explorer_db_query_seconds`, `explorer_db_queries_total` | `fn`, `outcome` (`ok`, `error`, `timeout`) | Kysely plugin and `log` hook on `db` |
| `explorer_cache_requests_total`, `explorer_cache_refresh_seconds` | `key_prefix`, `result` | `memo()` in `lib/cache.ts` |
| `explorer_render_seconds`, `explorer_render_db_queries` | `route` (pattern), `kind` (`document`, `rsc`, `prefetch`) | HTTP server hook in `lib/requestTiming.ts` |
| `explorer_request_errors_total` | `route`, `type` | `onRequestError` |

- **Query labels.** `fn` is the name of the function that called `.execute*()`,
  taken from the call stack. Server output is therefore built unminified
  (`experimental.serverMinification: false`).
  - Write DAL queries as named functions that call `.execute()` themselves; the
    label then needs no extra code.
  - Wrap a query in `withQueryLabel('name', () => …)` when it runs from an
    anonymous callback or shared helper.
  - Unlabelled queries fall back to `sql:<verb>:<first table>`.
  - Another Kysely instance, such as a worker pool, gets the same metrics with
    `new Kysely({ dialect, ...kyselyMetrics() })`.
- **Cache keys** must start with a constant name before the first `:`. That
  prefix becomes `key_prefix`.
- **Server-Timing** (`total`, `db;dur=…;desc="n queries"`, `cache;desc=hit|miss`)
  is written when Next sends the response head. Pages stream behind the root
  `loading.tsx`, so it covers only the work done before the first byte.
  - Streamed sections show up in `explorer_render_seconds` and
    `explorer_render_db_queries`, which are measured to the last byte.
  - `db;dur` is the sum of query times, so it can be larger than `total` when
    queries run in parallel.

### Explorer tables, search and filter chips (PAR-534)

Every table moves to one system (redesign spec §5.5). The accounts directory
(`app/accounts/page.tsx`) is the reference implementation.

- **Schema.** `defineTable` in `lib/tables/core.ts` declares a table once:
  filter fields (`enum`, `money`, `number`, `date`, `entity`, `text`,
  `boolean`), sortable fields, the default sort, quick filters and paging.
  `lib/tables/accounts.ts` is the example. It is plain data, so a server page
  can pass it to the client `FilterBar`.
- **URL state.** `?q=…&f=field:op:value&sort=-field&page=2`, one `f` per chip.
  `parseTableState(searchParams, table)` never throws: unknown fields, bad
  operators and malformed values are dropped. `tableHref` / `serializeTableState`
  write the canonical form back. Money is parsed from typed shorthand (`10k`,
  `$1,500`) into exact cents through `lib/money.ts`.
- **SQL.** `lib/sql/tableState.ts` turns the state into Kysely fragments
  (`compileTableState`) or applies it to a select builder (`applyTableState`).
  A typed column map must cover every declared filter and sort; only declared
  fields compile and every value is bound. Money compares as
  `CAST(? AS DECIMAL(19,2))`.
- **UI.** `DataTable`, `TableEmpty` and `TablePager` come from
  `@democracycraft/design-system/table` (server-rendered table, real links for
  sorting and rows, `aria-sort`, phone layout). `components/FilterBar.tsx` is
  the client search box, chips, quick filters, Columns menu and summary line;
  its filter editors open in the shared `Popover` (`/popover`).
- **Every filter must be index-backed or cheap.** Check the plan with `EXPLAIN`
  before offering a filter on a large table; if it needs an index, add a Flyway
  migration in the same PR or drop the filter.

### Explorer search, exports and big feeds (PAR-540)

- **Search.** `lib/services/search.ts` owns the rules; `lib/sql/search.ts` the
  queries. The typeahead (`searchPreview` and `searchSuggest` in
  `lib/actions/search.ts`, and `GET /api/search/preview` for the ⌘K palette,
  which can abort it) is prefix-only on name indexes, 2+ characters, and cached
  60 s per `(type, prefix)` under the `search-typeahead` memo key. `/search` adds
  contains matches on the small sets only: non-personal account names, firms and
  traded item keys. Player names stay prefix-only.
- **Keyset feeds.** `/transactions` and `/chestshop/sales` page on
  `(timestamp, id)` with `lib/sql/keyset.ts` and `lib/tables/feed.ts` (Newer,
  Older, jump to date), and count no further than 1,000. Each query is driven off
  one index chosen from the filters, with one ordered, LIMITed branch per chip
  value under a `UNION`; the comment above "Transaction explorer" in
  `lib/sql/ledger.ts` lists the drivers. Filters with no index (amount, account
  types, message or item text, price) only scan a bounded window
  (`lib/tables/scanWindow.ts`), and per-row checks are written as
  `(SELECT 1 … LIMIT 1) = 1`, because MariaDB may materialize an `EXISTS` as a
  semi-join over the whole history.
- **Full exports.** `GET /api/export/<dataset>?<page URL state>&format=csv|ndjson|json`
  streams up to 100k rows. The route handles sign-in, the per-viewer rate limit
  (10 a minute, Redis), the audit record (action `export`) and the row cap. To add a
  dataset, create `lib/export/datasets/<id>.ts` calling `defineExport` (the
  pattern is in the header of `lib/export/registry.ts`), add one import line to
  `lib/export/datasets/index.ts`, and pass `exportHrefs(id, state, table)` from
  `lib/export/href.ts` to the page's `ExportMenu`. `authorize` must repeat the
  page's own permission check.

### Business and Realty Explorer membership sources

TreasuryAPI reconciles optional per-group Business and Realty bindings independently
of LuckPerms. See [group reconciliation](group-reconciliation.md) for configuration,
lease expiry, provider contracts and deployment order (PAR-101).

### Business API-key account scope (PAR-448)

`/treasuryapi business issue <firmName> <accountId>` stores both `firm_id` and
`account_id` and signs both `firm` and `acc` claims. A null business `account_id`
retains legacy firm-wide access. The verifier requires an exact match between the
optional account claim and the persisted scope. Rotation retains both claims.
Transfers, history/feed, account listing and renaming enforce the account restriction
and current firm linkage. Firm-wide management requires a key without an account
restriction. Account-scoped webhooks store only the selected account as their
subscription scope so they cannot match sibling accounts. No schema migration is
required; the existing nullable columns support both scopes.
