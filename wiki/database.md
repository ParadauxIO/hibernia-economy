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
- Flyway runs with `mysql-connector-j` on its classpath (wire-compatible with
  MariaDB).

## Firm names

V32 adds `name_released` (default false) and a generated `reserved_display_name`
with a unique index. Both active and archived firms reserve their names until an
administrator runs `/business admin releasename <firmId>`. The atomic update only
releases archived firms. The generated value becomes NULL only when a firm is both
archived and explicitly released; active names are always protected by the index.
Existing archived firms stay reserved when the migration runs.

Archived rows retain their original `display_name`. Name lookups prefer the active
firm, then an unreleased archived firm, then the most recently created released
firm. Numeric IDs remain the unambiguous way to access older firms and resume a
disband. Release changes no account links, balances or ownership.

Deploy V32 together with the Business plugin, REST API and Explorer changes:
older application versions assume names identify a single row even across archives.

## Why the database owns balances

Balances live in a **materialized table maintained by database triggers**, not by
application code. Two independent writers post to the ledger — the Treasury plugin
(in-process) and `treasury-rest-api` (over HTTP) — so letting either compute
balances would race. Having MariaDB fold postings into the materialized balance
keeps it consistent under any mix of writers. Keep this in mind when writing
migrations that touch ledger tables or their triggers.

## Deploying a schema change

Schema deploys are **manual and gated** — there is no automatic "migrate on push".
The workflows live at the repo root under `.github/workflows/` (see
[ci-cd.md](ci-cd.md)):

| Workflow | What it does |
|---|---|
| `economy-flyway-info` | Read-only `flywayInfo` + `flywayValidate` against a chosen target. Run any time to preview what's pending. |
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

## Rollback

There is no automated rollback. The canonical recovery path is **restore from the
pre-deploy backup**: pause economy-touching services, restore, fix the migration,
re-run. This is deliberate — half-applied schema migrations have too many failure
modes to automate safely. The step-by-step version is in the
[release runbook](release-runbook.md#rollback).
