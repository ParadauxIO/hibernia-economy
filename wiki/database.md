# Database & schema

## One schema, one owner

The whole economy shares **one database**, and its schema has a single owner:
**`economy-flyway`**. Every component — the Treasury and Business plugins, the REST
API, the explorer — points at the same MariaDB and agrees on that schema.

- The schema is a series of versioned **Flyway migrations**:
  `economy-flyway/src/main/resources/db/migration/V<n>__*.sql`.
- **All** schema changes are new migrations. Don't hand-edit a live schema, and
  don't treat the `schema.sql` snapshots that exist inside other projects as
  authoritative — those are derived test fixtures or stale copies.
- A merged `V<n>` script is never edited, renamed or deleted, and a new one takes
  the next free version. CI enforces both
  ([`check-migrations.mjs`](../economy-flyway/scripts/check-migrations.mjs)) and
  upgrade-tests every change from the previous schema (see
  [ci-cd.md](ci-cd.md#migration-safety)).
- Flyway runs with `mysql-connector-j` on its classpath (wire-compatible with
  MariaDB).

## Firm names

V33 adds `name_released` (default false) and a generated `reserved_display_name`
with a unique index. Both active and archived firms reserve their names until an
administrator runs `/business admin releasename <firmId>`. The atomic update only
releases archived firms. The generated value becomes NULL only when a firm is both
archived and explicitly released; active names are always protected by the index.
Existing archived firms stay reserved when the migration runs.

Archived rows retain their original `display_name`. Name lookups prefer the active
firm, then an unreleased archived firm, then the most recently created released
firm. Numeric IDs remain the unambiguous way to access older firms and resume a
disband. Release changes no account links, balances or ownership.

Deploy V33 together with the Business plugin, REST API and Explorer changes:
older application versions assume names identify a single row even across archives.

## Why the database owns balances

Balances live in a **materialized table maintained by database triggers**, not by
application code. Two independent writers post to the ledger — the Treasury plugin
(in-process) and `treasury-rest-api` (over HTTP) — so letting either compute
balances would race. Having MariaDB fold postings into the materialized balance
keeps it consistent under any mix of writers. Keep this in mind when writing
migrations that touch ledger tables or their triggers.

`ledger_txn_volume` (V36) is maintained the same way: one row per transaction
with its settlement time and the sum of its positive postings, so the explorer's
time-window aggregates scan one covering index instead of joining
`ledger_txns` to `ledger_postings`. Its triggers only lock the row of the
transaction being written. Don't replace it with shared rollup rows (per hour or
per day): every transfer would contend for the same row, and taking it after the
ascending `FOR UPDATE` balance locks in `LedgerServiceImpl` can deadlock.

## Deploying a schema change

Schema deploys are **manual and gated** — there is no automatic "migrate on push".
The workflows live at the repo root under `.github/workflows/` (see
[ci-cd.md](ci-cd.md)):

| Workflow | What it does |
|---|---|
| `economy-flyway-info` | Read-only `flywayInfo` + `flywayValidate` against a chosen target. Run any time to preview what's pending; validation ignores pending migrations and fails on checksum drift or a missing applied script. |
| `economy-flyway-migrate` | Applies migrations to a target. Requires typing the target name to confirm; supports a `baseline` flag for a pre-existing DB. |
| `economy-flyway-staging-clean` | Wipes the **staging** schema for reproducible dry runs. Cannot target production. |

The intended flow:

1. Run **Info** against the target and read the plan.
2. Run **Migrate** against the same target to apply it.

Per-target secrets (`FLYWAY_URL` / `FLYWAY_USER` / `FLYWAY_PASSWORD` /
`FLYWAY_SCHEMA`) are configured as **GitHub Environments** — the targets the
workflows offer are `democracycraft` and `statecraft`. The migrate workflow runs
`flywayInfo` (the plan) immediately before applying, and again afterward as an
audit trail.

A manual run uses the migrations of the branch picked under *Use workflow from*
(the repository default is `develop`) — pick the ref you are actually deploying.

> [!IMPORTANT]
> Applying the schema and deploying the code are **separate, unsynchronised**
> steps: images roll out on merge to `main`, plugins on a server restart, and
> migrations only when someone runs the workflow. A migration that drops or
> renames something the running code still uses therefore needs a planned
> stop — see the **[release runbook](release-runbook.md)** for the full order
> of operations (backup → stop → migrate → deploy → verify → rollback).

## Local development

To refresh the local DemocracyCraft clone from production data, follow the
[staging database refresh runbook](../ops/dev-server/staging-refresh.md).
It covers the economy, ChestShop, Realty and LuckPerms databases, sanitization, writer
quiescence, migration verification and rollback snapshots.

For running services or full integration tests locally you need a MariaDB. Two
options:

- Point at a local MariaDB/MySQL you run yourself, applying the migrations with
  `./gradlew :economy-flyway:flywayMigrate` (configure the Flyway connection
  properties first).
- For JVM **integration tests**, you don't need to install anything — MariaDB4j
  unpacks and runs an embedded MariaDB and applies the schema automatically (see
  [build-and-test.md](build-and-test.md)).

## Tenancy

The same schema and images serve multiple servers (e.g. `democracycraft` and
`statecraft`). Tenanting is **configuration only** — a different database
URL/schema and secrets per environment — not a code or schema fork. The
`democracycraft` / `statecraft` names you'll see in `economy-explorer` and the
deploy workflows are these tenant identities; treat them as infrastructure, not
branding.

## Account read access — single source of truth

"Who may read an account's history/balance" is decided over the `account_access`
table (the ordered scale `VIEWER < MEMBER < AUTHORIZER`, soft-deleted via
`removed_at`). That rule used to be hand-restated in every client and had drifted,
so it lives in two **named SQL views** (`V22`) that all consumers read instead of
re-writing the level filter:

| View | Rule | Consumed by |
|---|---|---|
| `account_read_access_api` | `MEMBER` / `AUTHORIZER` only | `treasury-rest-api`, `treasury` plugin |
| `account_read_access_web` | `VIEWER` too | `economy-explorer` |

The two surfaces are **deliberately different**: the public REST API and in-game
plugin are strict (a read-only `VIEWER` is not a member), while the web explorer
also lets a `VIEWER` read — that's the government department-secretary case
(PAR-237). The OWNER path is checked separately by each caller (the owner isn't
necessarily an `account_access` row), so it isn't folded into the views. Change
the rule in `V<n>`, not in a mapper or a Kysely query — cross-side conformance
tests guard it (`MembershipAccessIT`, `MembershipServiceIT`, the explorer
integration suite).

## Published config — `economy_config`

Config files stay the source of truth for plugin tunables, but some of them (tax
rates first) are shown to players, and hand-copied numbers in the docs drift. So
the owning plugin **publishes** the values it is applying into `economy_config`
(`V32`) on every start and reload, and readers display them from there (PAR-80):

| Writer | Keys | Read by |
|---|---|---|
| Treasury (`ConfigPublishService`, owner `treasury`) | `tax.balance.{enabled,brackets}`, `tax.source-income-tax.{enabled,default-rate,plugin-rates}` | explorer docs live blocks (`/api/docs/tax-rates`), REST `GET /api/v1/config/tax` |

- One row per setting: `value_type` is `BOOLEAN`, `DECIMAL` or `JSON`; decimals are
  plain strings at their configured scale. The JSON shapes are documented on
  `ConfigPublishService`.
- `updated_at` only moves when a value changes, so it means "in effect since".
- A writer deletes its own (`owner`) keys it no longer publishes; it never touches
  another owner's rows.
- **Nothing reads this table to decide behaviour.** It is a display copy; the plugin
  keeps using its in-memory config. Publishing is fail-open: a failed write logs a
  warning and the plugin starts anyway.

To publish another setting, add the key in the owning plugin's publisher and teach
the reader to display it. In the explorer docs, a page shows it with a fenced
` ```live ` block naming the widget (see `lib/docsLive.ts`); the docs stay
statically generated and the `LiveDocBlocks` client component fills the block from
the running server.

## Rollback

There is no automated rollback. The canonical recovery path is **restore from the
pre-deploy backup**: pause economy-touching services, restore, fix the migration,
re-run. This is deliberate — half-applied schema migrations have too many failure
modes to automate safely. The step-by-step version is in the
[release runbook](release-runbook.md#rollback).

## ChestShop remaining space

V25 added `chestshop_shop.estimated_capacity`: remaining free room for the shop
item in units, not total container capacity. ChestShop captures empty-slot space
and matching partial-stack headroom alongside stock on create, trade and inventory
refresh, respecting item and container stack limits. NULL means unknown or an
admin/infinite shop; zero means full. `/find` already uses this column.

The public REST shop DTO exposes it as `currentSpace` (including item-detail
shop offers), and the explorer as `current_space`. Both use `stock_at` as the
observation timestamp. PAR-17 reuses V25 and the existing MarketApi capture path;
no duplicate `current_space` database column or new migration is needed.

## Progressive income tax totals

V36 added `income_tax_period`: one row per player per accounting period, holding
the income counted so far and the tax taken on it. `period_start` is the period's
start in epoch milliseconds, computed by Treasury from the configured period length
and anchor, so it doesn't depend on the connection time zone. The ledger calls
`IncomeTaxService` from `transferInternal` inside the payment's transaction; it
upserts the row (which locks it), reads the running total, and records the payment
and its tax before collecting, so concurrent payments to one player are taxed in turn
and the payment, total and tax commit or roll back together. A new period writes a
new row; old rows are history and can be pruned.
