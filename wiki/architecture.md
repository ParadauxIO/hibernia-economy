# Architecture

Hibernia Economy is an ecosystem of plugins and services arranged around a single
principle:

> **There is exactly one ledger, and it is the source of truth.**

Everything else either *records into* that ledger through a published API, or
*reads from* the shared database. No component keeps its own private notion of how
much money exists.

## The pieces and how they connect

```
   ┌──────────────────────── in-game (Paper) ────────────────────────┐
   │                                                                  │
   │   Business ─┐                                                    │
   │   ChestShop ─┼──▶ TreasuryApi / TaxApi ──▶  Treasury             │
   │   Realty ────┘        (Bukkit services)     (the double-entry    │
   │                                              ledger)             │
   └───────────────────────────────────────────────┬────────────────┘
                                                    │  reads + writes
                                                    ▼
   treasury-api-plugin ──issues JWT keys──▶ ┌───────────────────────┐
                                            │   shared MariaDB      │
   treasury-rest-api ──authenticated HTTP──▶│   (schema owned by    │
                                            │    economy-flyway)    │
                                            └───────────┬───────────┘
                                                        │  reads economy data
   economy-explorer (Next.js)  ◀───────────────────────┘  (+ writes a few
   (public website + /docs)                                control tables — see below)
```

### Money movement

All money movement funnels through **`LedgerService.transfer(...)`** in Treasury,
surfaced publicly as `TreasuryApi.transfer(TransferRequest)`. Each transfer writes
balanced debit/credit postings against a transaction header — classic
**double-entry accounting**. Balances are a materialized view maintained by the
database, not by application code, so concurrent writers (the plugin *and* the REST
API) can't drift.

Consequences that shape everything else:

- Business, ChestShop, and Realty **never** touch balances directly. They call
  `TreasuryApi`. A firm "having money" means a Treasury account of type `BUSINESS`.
- Idempotency keys on transactions make a re-submitted transfer a no-op rather than
  a double-spend — important because the same logical action can arrive from the
  plugin or over HTTP.

### Account types

Treasury accounts carry an `AccountType` (`PERSONAL`, `BUSINESS`, `GOVERNMENT`,
`SYSTEM`). This enum — not raw strings — is how ownership and behaviour are
distinguished (overdraft rules, one-personal-account-per-player, government
faucets/sinks, etc.).

## API surfaces (how components talk)

Plugins integrate through slim, published **`*-api` jars**, never by reaching into
each other's database:

- **`treasury-api`** (`io.paradaux:treasury-api`) — `TreasuryApi`, `TaxApi`, and
  the model types (`Account`, `TransferRequest`, `AccountType`, …). Registered at
  runtime as Bukkit services.
- **`business-api`** (`io.paradaux:business-api`) — `FirmApi`, `StaffApi`,
  `RolePermission`, and firm model types.

ChestShop, for example, compiles against both and resolves them at runtime as
Bukkit services — so it can route a shop sale into a firm's account without knowing
anything about Business's internals.

## The HTTP side

Some integrations live outside Minecraft:

- **`treasury-api-plugin`** issues **JWT API keys** — the credentials that
  authenticate REST callers.
- **`treasury-rest-api`** (Spring Boot) exposes the ledger over HTTP for those
  callers, with API-key auth and rate limiting. It writes to the **same** ledger
  tables as the plugin, which is exactly why balance maintenance lives in the
  database rather than in either writer.

## The read side

- **`economy-explorer`** (Next.js) is **read-only against the economy data** — it
  never writes the ledger, balances, accounts, or firms. It renders balances,
  prices, and money flow for the public, and serves the player/admin
  documentation at `/docs`.
- Its only direct writes are to its **own `explorer_*` tables** — identity links
  and link codes, access groups/capabilities/members, and the `explorer_audit`
  log. Every other mutation goes through the `treasury-rest-api` admin API with a
  SERVICE token (`TREASURY_API_BASE_URL` / `TREASURY_ADMIN_TOKEN`,
  `economy-explorer/lib/treasury.ts`): webhook subscriptions, API-key
  revoke/rotate, rate-limit overrides (ADT-14), and the privileged admin ops
  (firm disband/rename, account edits, arbitrary transfers).

### Who may read an account, and who owns writes

- **Access truth.** Whether a UUID may read an account's history/balance is
  decided over `account_access` via two named SQL views — `account_read_access_api`
  (MEMBER/AUTHORIZER) and `account_read_access_web` (VIEWER too) — so the rule
  isn't re-stated per client. The web surface is deliberately more permissive than
  the public API (a department-secretary VIEWER reads on the web only). See
  [database.md](database.md#account-read-access--single-source-of-truth).
- **Schema contract.** The shared schema is owned by `economy-flyway`. The
  explorer's full-schema kysely types are **generated from the migrated schema**
  into `economy-explorer/lib/db.generated.ts` (`npm run db:codegen`), and the
  `economy-explorer-schema-drift` workflow fails any PR whose migrations leave
  that file stale (ADT-5). The queries themselves still use the hand-maintained
  `DB` registry in `lib/db.ts` (only the `explorer_*` tables) plus ad-hoc row
  interfaces for raw SQL, so the gate flags a rename/drop for review but does
  not by itself break the TS build.

## Multi-tenancy

The same images run for more than one server (e.g. `democracycraft` and
`statecraft`). Tenanting is configuration-only (database URL, secrets, Redis) —
there is no per-tenant code fork. See [database.md](database.md) and
[ci-cd.md](ci-cd.md) for how environments and image tags are wired.

## The shared schema

All of the above agree on one database whose schema is owned by
**`economy-flyway`**. Schema changes are versioned migrations applied through gated
workflows — never ad-hoc DDL. See [database.md](database.md).

## Frameworks underneath

The Paper plugins are built on **`hibernia-framework`** (a git submodule, consumed
as `io.paradaux:hibernia-framework`): Brigadier-based `@Command` handling,
type-safe configuration, and i18n. Dependency injection is **Guice**; persistence
is **MyBatis** (annotation-based mappers) over **HikariCP**/MariaDB. See
[components.md](components.md) for per-project detail.
