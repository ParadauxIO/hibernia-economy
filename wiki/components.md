# Components

Every project in the monorepo, what it's responsible for, and how it's put
together. For how they fit as a whole, see [architecture.md](architecture.md).

## JVM build topology

All JVM projects are part of **one** Gradle build (`settings.gradle.kts` at the
root includes them all). Group is `io.paradaux`, Java toolchain is 21, plugin
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

- **Stack:** same shape as Treasury (Paper · Guice · MyBatis · HikariCP ·
  `hibernia-framework`). Libs relocated under `io.paradaux.business.libs.*`.
- **Layering:** `commands/` → `services/`(+`impl/`) → `mappers/`.
- **Depends on** `treasury-api` (compile/runtime) to move money, and registers its
  own API as a Bukkit service.

### `business/business-api/` — public Business API (library jar)

`FirmApi`, `StaffApi`, `RolePermission`, and firm model types. Published as
`io.paradaux:business-api`. Consumed by ChestShop (and anything else that needs to
route money to a firm or check a staff permission).

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
- **Single Gradle module** (`plugin/`) compiled against the **Paper 1.21.11** API,
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
  (Kysely query functions, pure DAL). Route handlers exist only for auth/health.
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
