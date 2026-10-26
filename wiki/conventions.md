# Conventions

The rules that keep a multi-project monorepo coherent. Most are enforced in
review; the load-bearing ones hold even if you skip everything else.

## Load-bearing rules

1. **The ledger is the source of truth.** Treasury owns every balance. Never store
   balances or move money outside **`TreasuryApi.transfer(...)`**, and never let one
   plugin read or write another's tables — integrate through the published `*-api`.
2. **`AccountType` is an enum, not a string.** Use `PERSONAL` / `BUSINESS` /
   `GOVERNMENT` / `SYSTEM`, never raw strings.
3. **Public API goes in `*-api` subprojects** (`treasury-api`, `business-api`).
   Never expose plugin internals as API.
4. **Schema changes are Flyway migrations** in `economy-flyway/` — a new
   `V<n>__*.sql`. Don't hand-edit live schema or treat the in-project `schema.sql`
   snapshots as authoritative.
5. **Don't auto-switch branches.** If a checkout is on an unexpected branch, ask —
   it may be intentional WIP.

## Branch model & issue tracking

- **`develop`** is the integration branch — all work targets it.
- **`main`** is for **release tagging** only. The single recurring PR is
  `develop` → `main`, whose body lists every `PAR-…` in the release.
- **Issues live in Tesks, not GitHub Issues.** Raise bugs/ideas in the
  [Discord](https://discord.gg/b88K3ETeHS); a `PAR-…` issue is created and linked.
  No untracked work.
- **Reference the Tesks ref in every commit** (e.g. `Fix firm default account
  (PAR-62)`), in the imperative mood.
- **Never add CI tooling or assistants as commit co-authors.**

## Namespace & coordinates

- All JVM code is under **`io.paradaux.*`**.
- Build coordinates are `io.paradaux:<artifact>` (e.g. `io.paradaux:treasury-api`).
- The vendored ChestShop fork lives under `io.paradaux.chestshop.*`.

## Architectural layering

Every project enforces a strict, one-directional layering. An **entrypoint** takes
input, a **service** owns business logic and transactions, and a **persistence
layer** owns all DB access. Entrypoints never touch persistence directly;
persistence holds no business logic.

- **Paper plugins** (`treasury`, `business`, `treasury-api-plugin`, `chestshop`):
  `commands/` & `events/`|`listeners/` → `services/`(+`impl/`) → `mappers/`
  (MyBatis annotation SQL). DI is **Guice** (`guice/` modules). A command must not
  inject a mapper.
- **Spring Boot** (`treasury-rest-api`): `controller/` (DTOs) → `service/`
  (`@Transactional`) → `mapper/`. DI is **Spring** (constructor injection).
- **Next.js** (`economy-explorer`): Server Component page / route → service →
  `lib/sql/*` DAL (Kysely; pure queries, no business logic). It's **read-only**
  against the economy.

When a project's actual layout and this list disagree, the project wins — but it's
expected to match. New code that skips a layer (a command hitting a mapper, a page
running business logic inline) is a defect.

## Build conventions

- **One root build.** Plugin versions are centralized in the root `build.gradle.kts`
  (`apply false`); subprojects apply them without a version. Cross-project deps are
  `project(...)` deps. See [build-and-test.md](build-and-test.md).
- **Shadow 9:** disable `:jar` (`tasks.jar { enabled = false }`) in any module that
  shades, or the thin jar overwrites the fat one.
- **ChestShop is a fork we own.** DC-specific changes are expected; prefer the
  surrounding file's style for readability, but you don't need to preserve upstream
  idioms.

## Plugin message colours

Keep the four plugin `messages.properties` files on the same chat palette:

| Role | Colour | Contrast on `#404040` |
|---|---|---|
| Brand, headings, commands, highlighted values | `#aabfff` | 5.73:1 |
| Labels and metadata | `#b8b8b8` | 5.23:1 |
| Errors, rejection, deductions | `#ff9999` | 5.07:1 |
| Success and money | `<green>` (`#55ff55`) | 7.81:1 |
| Warnings and caution | `<yellow>` (`#ffff55`) | 9.72:1 |

Use `{secbegin}` / `{secend}` for ordinary highlights. Keep warning colours
meaningful; navigation links, identifiers, and ordinary headings use the blue
accent. Preserve click/hover actions and explanatory text when adjusting colours.
These ratios assume opaque text against a solid dark-grey background, not every
possible Minecraft scene or chat-opacity setting. Check changes in-game when a
client is available. Server-customized locale files may need the same updates.

## Documentation

- **Player/admin-facing** behaviour changes → update the user guide in
  [`economy-explorer/docs/`](../economy-explorer/docs/index.md).
- **Developer-facing** changes (architecture, build, conventions) → update this
  wiki.
- Keep both in the same PR as the change they describe.
